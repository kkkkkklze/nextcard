package com.klze.nextcard.core.card;

import com.klze.nextcard.core.draw.CardClass;
import com.klze.nextcard.core.effect.EffectClause;
import com.klze.nextcard.core.tag.TagIndex;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 卡片定义（v1.1 §4.1-1）。纯数据：等级唯一、标签集合、A/B/C 类、所属体系、前置标签、效果子句表。
 *
 * <p>校验规则（全部 fail-fast，加载期硬错误）：等级 1–5；标签必须已注册；A ⇒ tier≥3 且必须声明体系
 * （「A 类与三级卡同时入场」，第一批裁定 2）；C ⇒ tier≥5（C1 裁定）；非 A 卡不得声明体系。
 * {@code requires} 是<b>标签谓词</b>（v1.1 第五批裁定）：拥有 ≥1 张带该标签的卡即满足，
 * 判断标签与普通标签同一谓词——跨卡校验（引用的标签已注册、每卡至少一个非判断标签）在
 * {@link CardIndex#build}。没有 stack 字段——每卡唯一拥有（第一批裁定 3：不能有同名卡）。</p>
 */
public record CardDefinition(ResourceLocation id, int tier, CardClass cardClass,
                             Set<ResourceLocation> tags, Optional<ResourceLocation> system,
                             List<ResourceLocation> requires, List<EffectClause> effects) {

    public CardDefinition {
        tags = Set.copyOf(tags);
        requires = List.copyOf(requires);
        effects = List.copyOf(effects);
    }

    /** 不含 id 与 effects 的 JSON 体（id 来自文件路径；effects 走 {@link com.klze.nextcard.core.effect.EffectClauses} 词表）。 */
    public record Body(int tier, CardClass cardClass, Set<ResourceLocation> tags,
                       Optional<ResourceLocation> system, List<ResourceLocation> requires) {
        public static final Codec<Body> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.intRange(1, 5).fieldOf("tier").forGetter(Body::tier),
                CardClass.CODEC.fieldOf("card_class").forGetter(Body::cardClass),
                ResourceLocation.CODEC.listOf().xmap(CardDefinition::toTagSet, set -> List.copyOf(set))
                        .fieldOf("tags").forGetter(Body::tags),
                ResourceLocation.CODEC.optionalFieldOf("system").forGetter(Body::system),
                ResourceLocation.CODEC.listOf().optionalFieldOf("requires", List.of()).forGetter(Body::requires)
        ).apply(i, Body::new));
    }

    /** 保序去重集合；返回接口类型，让 xmap 的类型参数落在 Set 而不是实现类上。 */
    private static Set<ResourceLocation> toTagSet(List<ResourceLocation> list) {
        return new LinkedHashSet<>(list);
    }

    public static CardDefinition of(ResourceLocation id, Body body, List<EffectClause> effects) {
        return new CardDefinition(id, body.tier(), body.cardClass(), body.tags(),
                body.system(), body.requires(), effects);
    }

    /** 逐卡校验，返回错误清单（空 = 合法）。跨卡规则（requires 可解析）在 {@link CardIndex#build}。 */
    public List<String> validate(TagIndex tags) {
        List<String> errors = new ArrayList<>();
        if (tier < 1 || tier > 5) {
            errors.add(id + ": tier must be 1..5, got " + tier);
        }
        for (ResourceLocation tag : this.tags) {
            if (!tags.contains(tag)) {
                errors.add(id + ": unregistered tag " + tag);
            }
        }
        switch (cardClass) {
            case A -> {
                if (tier < 3) errors.add(id + ": class A (方向卡) enters at tier 3, got " + tier);
                if (system.isEmpty()) errors.add(id + ": class A must declare a system");
            }
            case C -> {
                if (tier < 5) errors.add(id + ": class C (质变卡) enters at tier 5 (C1), got " + tier);
                if (system.isPresent()) errors.add(id + ": only class A may declare a system");
            }
            case B -> {
                if (system.isPresent()) errors.add(id + ": only class A may declare a system");
            }
        }
        return errors;
    }
}
