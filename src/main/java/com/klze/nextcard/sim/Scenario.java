package com.klze.nextcard.sim;

import com.klze.nextcard.core.card.CardDefinition;
import com.klze.nextcard.core.card.CardIndex;
import com.klze.nextcard.core.draw.CardClass;
import com.klze.nextcard.core.draw.DrawProfile;
import com.klze.nextcard.core.draw.DrawSchedule;
import com.klze.nextcard.core.load.LoadResult;
import com.klze.nextcard.core.pool.PoolIndex;
import com.klze.nextcard.core.tag.TagDefinition;
import com.klze.nextcard.core.tag.TagIndex;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 参考场景：与 {@code data/nextcard/} 的示例内容一一对应的代码侧构造。
 * 蒙特卡洛与纯逻辑门都喂它；ContentParityTest 断言 JSON 内容与这里的标识一致——
 * 改示例内容时两处必须同步（G2 保证 core 不含这些字面量，这里是 sim 包，允许引用内容）。
 */
public final class Scenario {

    public static final ResourceLocation TAG_ATTACK = rl("attack");
    public static final ResourceLocation TAG_FIRE = rl("fire");
    public static final ResourceLocation TAG_POISON = rl("poison");
    /** 判断标签（judgment_only）：不参与概率，只做 requires 谓词（v1.1 §5.5）。 */
    public static final ResourceLocation TAG_COUNTER = rl("counter");
    public static final ResourceLocation SYSTEM_POISON = rl("poison");

    public static final List<ResourceLocation> STARTERS = List.of(
            rl("starter_1"), rl("starter_2"), rl("starter_3"), rl("starter_4"), rl("starter_5"));
    public static final ResourceLocation VENOM_EDGE = rl("venom_edge");
    public static final ResourceLocation VENOM_CASCADE = rl("venom_cascade");
    public static final ResourceLocation PHOENIX_BREATH = rl("phoenix_breath");
    public static final ResourceLocation CHAIN_REACTION = rl("chain_reaction");

    public static final ResourceLocation PROFILE_FIRST = rl("first");
    public static final ResourceLocation PROFILE_STANDARD_EARLY = rl("standard_early");
    public static final ResourceLocation PROFILE_STANDARD = rl("standard");

    private final TagIndex tags;
    private final CardIndex cards;
    private final PoolIndex pools;
    private final Map<ResourceLocation, DrawProfile> profiles;
    private final DrawSchedule schedule;

    private Scenario(TagIndex tags, CardIndex cards, PoolIndex pools,
                     Map<ResourceLocation, DrawProfile> profiles, DrawSchedule schedule) {
        this.tags = tags;
        this.cards = cards;
        this.pools = pools;
        this.profiles = profiles;
        this.schedule = schedule;
    }

    public static Scenario load() {
        TagIndex tags = new TagIndex(Map.of(
                TAG_ATTACK, new TagDefinition(TAG_ATTACK, "tag.nextcard.attack", "B0BEC5", "attack", "", false),
                TAG_FIRE, new TagDefinition(TAG_FIRE, "tag.nextcard.fire", "E25822", "fire", "", false),
                TAG_POISON, new TagDefinition(TAG_POISON, "tag.nextcard.poison", "7CB342", "poison", "", false),
                TAG_COUNTER, new TagDefinition(TAG_COUNTER, "tag.nextcard.counter", "8BC34A", "counter", "", true)));
        List<CardDefinition> cards = List.of(
                card(rl("starter_1"), 1, CardClass.B, TAG_ATTACK),
                card(rl("starter_2"), 1, CardClass.B, TAG_ATTACK),
                card(rl("starter_3"), 1, CardClass.B, TAG_ATTACK, TAG_FIRE),
                card(rl("starter_4"), 1, CardClass.B, TAG_FIRE),
                card(rl("starter_5"), 1, CardClass.B, TAG_ATTACK, TAG_POISON),
                card(rl("ember_lash"), 2, CardClass.B, TAG_FIRE, TAG_ATTACK),
                card(rl("ash_guard"), 2, CardClass.B, TAG_FIRE),
                card(rl("cinder_step"), 2, CardClass.B, TAG_FIRE, TAG_POISON),
                new CardDefinition(VENOM_EDGE, 3, CardClass.A, Set.of(TAG_POISON, TAG_ATTACK, TAG_COUNTER),
                        Optional.of(SYSTEM_POISON), List.of(), List.of()),
                card(rl("twin_fang"), 3, CardClass.B, TAG_ATTACK, TAG_POISON, TAG_COUNTER),
                card(rl("iron_root"), 3, CardClass.B, TAG_POISON),
                card(rl("storm_pulse"), 4, CardClass.B, TAG_ATTACK),
                card(rl("grave_bloom"), 4, CardClass.B, TAG_POISON, TAG_FIRE),
                new CardDefinition(PHOENIX_BREATH, 5, CardClass.A, Set.of(TAG_FIRE, TAG_ATTACK),
                        Optional.of(rl("rebirth")), List.of(), List.of()),
                new CardDefinition(VENOM_CASCADE, 5, CardClass.C, Set.of(TAG_POISON, TAG_ATTACK),
                        Optional.empty(), List.of(TAG_POISON), List.of()),
                new CardDefinition(CHAIN_REACTION, 5, CardClass.C, Set.of(TAG_POISON, TAG_ATTACK),
                        Optional.empty(), List.of(TAG_COUNTER), List.of()),
                card(rl("last_stand"), 5, CardClass.B, TAG_ATTACK));
        CardIndex index = checked(CardIndex.build(cards, tags));
        PoolIndex pools = PoolIndex.of(index);

        DrawProfile first = new DrawProfile(Optional.of(STARTERS), List.of(), DrawProfile.Grant.PICK_ONE);
        DrawProfile standardEarly = slotsProfile(List.of(DrawProfile.Filter.NONE));
        DrawProfile standard = slotsProfile(List.of(
                new DrawProfile.Filter(Optional.of(CardClass.A)), DrawProfile.Filter.NONE));
        Map<ResourceLocation, DrawProfile> profiles = Map.of(
                PROFILE_FIRST, first, PROFILE_STANDARD_EARLY, standardEarly, PROFILE_STANDARD, standard);

        DrawSchedule schedule = new DrawSchedule(List.of(
                new DrawSchedule.Row(new DrawSchedule.DrawRange(1, 1), PROFILE_FIRST, Map.of()),
                new DrawSchedule.Row(new DrawSchedule.DrawRange(2, 4), PROFILE_STANDARD_EARLY,
                        Map.of(1, 70, 2, 30)),
                new DrawSchedule.Row(new DrawSchedule.DrawRange(5, 8), PROFILE_STANDARD,
                        Map.of(1, 45, 2, 35, 3, 20)),
                new DrawSchedule.Row(new DrawSchedule.DrawRange(9, 11), PROFILE_STANDARD,
                        Map.of(1, 30, 2, 30, 3, 25, 4, 10, 5, 5)),
                new DrawSchedule.Row(new DrawSchedule.DrawRange(12, 15), PROFILE_STANDARD,
                        Map.of(1, 10, 2, 30, 3, 25, 4, 15, 5, 20))));
        return new Scenario(tags, index, pools, profiles, schedule);
    }

    private static DrawProfile slotsProfile(List<DrawProfile.Filter> filters) {
        List<DrawProfile.Slot> slots = filters.stream()
                .map(filter -> new DrawProfile.Slot(filter, DrawProfile.Sampling.TAG_WEIGHTED))
                .toList();
        return new DrawProfile(Optional.empty(), slots, DrawProfile.Grant.PICK_ONE);
    }

    private static CardDefinition card(ResourceLocation id, int tier, CardClass cardClass,
                                       ResourceLocation... tags) {
        return new CardDefinition(id, tier, cardClass, Set.of(tags), Optional.empty(), List.of(), List.of());
    }

    private static <T> T checked(LoadResult<T> result) {
        if (!result.ok()) {
            throw new IllegalStateException("scenario content invalid: " + result.errors());
        }
        return result.value();
    }

    private static ResourceLocation rl(String path) {
        return new ResourceLocation("nextcard", path);
    }

    public TagIndex tags() { return tags; }
    public CardIndex cards() { return cards; }
    public PoolIndex pools() { return pools; }
    public Map<ResourceLocation, DrawProfile> profiles() { return profiles; }
    public DrawSchedule schedule() { return schedule; }
}
