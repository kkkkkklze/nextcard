package com.klze.nextcard.core.draw;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

/**
 * 抽卡 profile（v1.0 §4.1-5）：一次抽卡的全部可变性质都是数据。
 * 首抽（固定五张、5 选 1，第三批裁定 7）与标准抽（首槽必 A，自 A 入场起生效，第四批裁定 1）
 * 是两个 profile；代码里没有 if(firstDraw)、没有 if(cardClass==A)。
 */
public record DrawProfile(Optional<List<ResourceLocation>> fixedCards, List<Slot> slots, Grant grant) {

    public enum Grant {
        /** 展示候选后选一张（标准抽与首抽）。 */
        PICK_ONE,
        /** 候选全部给玩家（数据保留，供整合包自定义 profile）。 */
        GRANT_ALL;

        public static final Codec<Grant> CODEC =
                Codec.STRING.xmap(s -> Grant.valueOf(s.toUpperCase()), Grant::name);
    }

    public enum Sampling {
        /** 标签加权采样（§5.3）：80/20 由 WeightModel 决定。 */
        TAG_WEIGHTED;

        public static final Codec<Sampling> CODEC =
                Codec.STRING.xmap(s -> Sampling.valueOf(s.toUpperCase()), Sampling::name);
    }

    /** 槽位过滤器（开放结构：M0 只有 cardClass，后续按需加标签/体系过滤——加字段不加分支）。 */
    public record Filter(Optional<CardClass> cardClass) {
        public static final Filter NONE = new Filter(Optional.empty());
        public static final Codec<Filter> CODEC = RecordCodecBuilder.create(i -> i.group(
                CardClass.CODEC.optionalFieldOf("cardClass").forGetter(Filter::cardClass)
        ).apply(i, Filter::new));
    }

    /** 一个槽位产出一候选；offer 数 = slots 数量。 */
    public record Slot(Filter filter, Sampling sampling) {
        public static final Codec<Slot> CODEC = RecordCodecBuilder.create(i -> i.group(
                Filter.CODEC.optionalFieldOf("filter", Filter.NONE).forGetter(Slot::filter),
                Sampling.CODEC.optionalFieldOf("sampling", Sampling.TAG_WEIGHTED).forGetter(Slot::sampling)
        ).apply(i, Slot::new));
    }

    private static Codec<DrawProfile> fixedCodec() {
        return RecordCodecBuilder.create(i -> i.group(
                ResourceLocation.CODEC.listOf().fieldOf("cards").forGetter(DrawProfile::fixedCardsOrThrow),
                Grant.CODEC.fieldOf("grant").forGetter(DrawProfile::grant)
        ).apply(i, (cards, grant) -> new DrawProfile(Optional.of(cards), List.of(), grant)));
    }

    private static Codec<DrawProfile> slotsCodec() {
        return RecordCodecBuilder.create(i -> i.group(
                Slot.CODEC.listOf().fieldOf("slots").forGetter(DrawProfile::slots),
                Grant.CODEC.fieldOf("grant").forGetter(DrawProfile::grant)
        ).apply(i, (slots, grant) -> new DrawProfile(Optional.empty(), slots, grant)));
    }

    public static final Codec<DrawProfile> CODEC =
            Codec.either(fixedCodec(), slotsCodec()).xmap(
                    either -> either.map(fixed -> fixed, slots -> slots),
                    profile -> profile.fixedCards().isPresent()
                            ? Either.left(profile)
                            : Either.right(profile));

    /** 固定发牌型 profile 的读取捷径（slots 型调用即抛）。 */
    private List<ResourceLocation> fixedCardsOrThrow() {
        return fixedCards.orElseThrow(() -> new IllegalStateException("not a fixed-cards profile"));
    }

    /** 加载期形状校验（fail-fast）。 */
    public List<String> validate() {
        List<String> errors = new java.util.ArrayList<>();
        if (fixedCards.isPresent()) {
            if (fixedCards.get().isEmpty()) errors.add("fixed-cards profile must list at least one card");
            if (!slots.isEmpty()) errors.add("profile cannot have both fixed cards and slots");
        } else {
            if (slots.isEmpty()) errors.add("slot profile must declare at least one slot");
        }
        return errors;
    }
}
