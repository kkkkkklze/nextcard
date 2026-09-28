package com.klze.nextcard.logic;

import com.klze.nextcard.core.card.CardDefinition;
import com.klze.nextcard.core.card.CardIndex;
import com.klze.nextcard.core.draw.CardClass;
import com.klze.nextcard.core.effect.EffectClause;
import com.klze.nextcard.core.effect.EffectHost;
import com.klze.nextcard.core.effect.MechanicProfile;
import com.klze.nextcard.core.effect.ModifierClause;
import com.klze.nextcard.core.effect.Reconciler;
import com.klze.nextcard.sim.Scenario;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生效宿主（{@code core/effect/EffectHost}）的三条形状断言：只有重算、攒脏一次算、
 * 先重算完再通知。这三条一旦破，M1 的属性挂接就会长出"撤销"这条第二条路。
 */
public class EffectHostTest {

    private static final ResourceLocation WEAK = new ResourceLocation("nextcard", "weaken");
    private static final ResourceLocation SHARP = new ResourceLocation("nextcard", "sharpen");

    @Test
    public void severalGrantsCollapseIntoOneFlush() {
        EffectHost host = new EffectHost(() -> index(
                card(WEAK, "window.length", 0.30),
                card(SHARP, "charge.rate", 0.20)));
        AtomicInteger notifications = new AtomicInteger();

        host.grant("p1", WEAK);
        host.grant("p1", SHARP);
        assertTrue(host.isDirty("p1"));

        int processed = host.flush((holder, snapshot, cards, slots) -> notifications.incrementAndGet());
        assertEquals(1, processed, "两个 grant 只在一次 flush 里算完");
        assertEquals(1, notifications.get(), "监听者只该被打扰一次");
        assertFalse(host.isDirty("p1"));
        assertEquals(0, host.flush((holder, snapshot, cards, slots) -> notifications.incrementAndGet()),
                "不脏就不该有第二轮");
        assertEquals(1, notifications.get());

        MechanicProfile profile = host.profile("p1");
        assertEquals(0.30, profile.number("window.length", 0.0), 1e-9);
        assertEquals(0.20, profile.number("charge.rate", 0.0), 1e-9);
    }

    /** 监听者读到的必须是算完之后的状态，而不是"这张卡刚加、那张还没算"。 */
    @Test
    public void listenersAlwaysSeeTheFinishedProfile() {
        EffectHost host = new EffectHost(() -> index(
                card(WEAK, "window.length", 0.30),
                card(SHARP, "window.length", 0.50)));
        List<Double> seen = new ArrayList<>();

        host.grant("p1", WEAK);
        host.grant("p1", SHARP);
        host.flush((holder, snapshot, cards, slots) ->
                seen.add(host.profile(holder).number("window.length", -1.0)));

        assertEquals(List.of(0.80), seen, "通知时两条来源都已折进同一个值");
    }

    /** 撤卡不写反向代码：重算一次，值自己回到基准，差量里带 CARD_LOST。 */
    @Test
    public void revokingRecomputesInsteadOfUndoing() {
        EffectHost host = new EffectHost(() -> index(card(WEAK, "window.length", 0.30), card(SHARP, "window.length", 0.50)));
        host.grant("p1", WEAK);
        host.grant("p1", SHARP);
        List<Reconciler.Change> reported = new ArrayList<>();
        List<Reconciler.SlotChange> slots = new ArrayList<>();
        host.flush((holder, snapshot, cards, changes) -> {
            reported.addAll(cards);
            slots.addAll(changes);
        });
        assertEquals(0.80, host.profile("p1").number("window.length", 0.0), 1e-9);

        assertTrue(host.revoke("p1", SHARP));
        host.flush((holder, snapshot, cards, changes) -> {
            reported.addAll(cards);
            slots.addAll(changes);
        });

        assertEquals(0.30, host.profile("p1").number("window.length", 0.0), 1e-9, "回退由重算给出");
        assertTrue(reported.contains(new Reconciler.Change(Reconciler.Kind.CARD_LOST, SHARP)),
                "差量应点名被撤掉的卡: " + reported);
        assertEquals(0.80, slots.get(slots.size() - 1).from(), 1e-9,
                "槽位差量的起点是上一轮折好的净值得 0.80，不是被撤那张卡自己的 0.50");
        assertEquals(0.30, slots.get(slots.size() - 1).to(), 1e-9);
    }

    /**
     * {@code per_stack} 是同一条"每层多少"的第二种写法，必须与
     * {@code mechanic_modifier + source:{counter, per_layer}} 折出同一个数——
     * 只读后者的话，卡面写了 {@code per_stack} 会静默不生效（最难查的一种假绿）。
     */
    @Test
    public void perStackAndLayeredModifierFoldToTheSameNumber() {
        EffectHost host = new EffectHost(() -> index(
                stackCard(WEAK, "wall", 0.05),
                layeredCard(SHARP, "wall", 0.05)));
        host.grant("p1", WEAK);
        host.grant("p2", SHARP);
        host.flush((holder, snapshot, cards, slots) -> {
        });

        assertEquals(0.0, host.profile("p1").channel("damage_reduction"), 1e-9, "零层就不该有减免");
        host.counters().gain(new com.klze.nextcard.core.effect.CounterStore.Key("p1", "wall"), 2,
                new com.klze.nextcard.core.effect.CounterStore.Rule(3, 0.0,
                        com.klze.nextcard.core.effect.CounterStore.Expiry.PER_LAYER), 0.0);
        host.counters().gain(new com.klze.nextcard.core.effect.CounterStore.Key("p2", "wall"), 2,
                new com.klze.nextcard.core.effect.CounterStore.Rule(3, 0.0,
                        com.klze.nextcard.core.effect.CounterStore.Expiry.PER_LAYER), 0.0);
        host.markDirty("p1");
        host.markDirty("p2");
        host.flush((holder, snapshot, cards, slots) -> {
        });

        assertEquals(0.10, host.profile("p1").channel("damage_reduction"), 1e-9,
                "per_stack 写下的每层 5% 要真的进到折叠结果里");
        assertEquals(host.profile("p1").channel("damage_reduction"),
                host.profile("p2").channel("damage_reduction"), 1e-9, "两种写法必须给同一个数");
    }

    /** per_stack 的层数就是这条资源自己，再写 source 会指向第二种解释——加载期就拦。 */
    @Test
    public void perStackMustBeAPlainValue() {
        EffectClause bad = new com.klze.nextcard.core.effect.StackClause("wall",
                com.klze.nextcard.core.effect.StackClause.Scope.PERSISTENT, 3, 0.0, List.of(),
                List.of(new ModifierClause("channel.damage_reduction", 0.0, "", false, "other", 0.05,
                        List.of())),
                List.of());
        CardDefinition card = new CardDefinition(WEAK, 1, CardClass.B, Set.of(Scenario.TAG_ATTACK),
                Optional.empty(), List.of(), List.of(bad));
        List<String> errors = com.klze.nextcard.core.effect.EffectClauses.validateReferences(List.of(card));
        assertTrue(errors.stream().anyMatch(e -> e.contains("plain {target, value}")), errors.toString());
    }

    private static CardDefinition stackCard(ResourceLocation id, String stackId, double perLayer) {
        EffectClause stack = new com.klze.nextcard.core.effect.StackClause(stackId,
                com.klze.nextcard.core.effect.StackClause.Scope.PERSISTENT, 3, 0.0, List.of(),
                List.of(new ModifierClause("channel.damage_reduction", perLayer, "", 0.0, List.of())),
                List.of());
        return new CardDefinition(id, 1, CardClass.B, Set.of(Scenario.TAG_ATTACK),
                Optional.empty(), List.of(), List.of(stack));
    }

    private static CardDefinition layeredCard(ResourceLocation id, String stackId, double perLayer) {
        EffectClause modifier = new ModifierClause("channel.damage_reduction", 0.0, "", false, stackId,
                perLayer, List.of());
        return new CardDefinition(id, 1, CardClass.B, Set.of(Scenario.TAG_ATTACK),
                Optional.empty(), List.of(), List.of(modifier));
    }

    private static CardDefinition card(ResourceLocation id, String slot, double value) {
        EffectClause modifier = new ModifierClause(slot, value, "", 0.0, List.of());
        return new CardDefinition(id, 1, CardClass.B, Set.of(Scenario.TAG_ATTACK),
                Optional.empty(), List.of(), List.of(modifier));
    }

    private static CardIndex index(CardDefinition... cards) {
        return CardIndex.build(List.of(cards), Scenario.load().tags()).value();
    }
}
