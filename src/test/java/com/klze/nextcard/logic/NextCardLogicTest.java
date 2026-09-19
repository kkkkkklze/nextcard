package com.klze.nextcard.logic;

import com.klze.nextcard.core.card.CardDefinition;
import com.klze.nextcard.core.card.CardIndex;
import com.klze.nextcard.core.draw.CardClass;
import com.klze.nextcard.core.draw.DrawEngine;
import com.klze.nextcard.core.draw.DrawProfile;
import com.klze.nextcard.core.draw.DrawResult;
import com.klze.nextcard.core.draw.DrawSchedule;
import com.klze.nextcard.core.draw.TagWeights;
import com.klze.nextcard.core.draw.WeightModel;
import com.klze.nextcard.core.effect.EffectSnapshot;
import com.klze.nextcard.core.effect.Reconciler;
import com.klze.nextcard.core.pool.PoolIndex;
import com.klze.nextcard.sim.Scenario;
import com.klze.nextcard.core.tag.TagIndex;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯逻辑门（G1/G3 的断言部分）。全部不启游戏；参考场景见 {@link Scenario}。
 */
public class NextCardLogicTest {

    /** G1：空内容下引擎全链路可调用、行为可断言——删光 data/nextcard 游戏照跑（铁律 1）。 */
    @Test
    public void emptyContentEngineStaysCallable() {
        CardIndex index = CardIndex.empty();
        PoolIndex pools = PoolIndex.of(index);
        assertTrue(pools.isEmpty(), "no cards -> no pools");
        assertTrue(index.systemsOf(Set.of()).isEmpty());

        DrawProfile standard = Scenario.load().profiles().get(Scenario.PROFILE_STANDARD);
        DrawResult result = DrawEngine.draw(index, pools, standard, Map.of(1, 100), Set.of(), new Random(1));
        assertTrue(result.isEmpty(), "empty content must draw nothing");
        assertEquals(DrawResult.Status.NO_CARDS, result.status());
        assertEquals(DrawResult.NO_CARDS_MESSAGE, result.log().get(0));

        EffectSnapshot empty = EffectSnapshot.compute(index, Set.of());
        assertTrue(Reconciler.diff(empty, empty).isEmpty());
    }

    /** 池投影：多标签卡进多个池、列表确定性有序。 */
    @Test
    public void poolProjectionDerivesFromCards() {
        Scenario scenario = Scenario.load();
        PoolIndex pools = scenario.pools();

        ResourceLocation starter3 = rl("starter_3");
        assertTrue(pools.cardsIn(1, Scenario.TAG_ATTACK).contains(starter3), "multi-tag card in each tag pool");
        assertTrue(pools.cardsIn(1, Scenario.TAG_FIRE).contains(starter3));
        assertEquals(Set.of(1, 2, 3, 4, 5), pools.availableTiers(), "scenario covers all five tiers");

        List<ResourceLocation> tier1 = pools.allInTier(1);
        assertEquals(new HashSet<>(tier1).size(), tier1.size(), "allInTier dedupes multi-tag cards");
        assertEquals(5, tier1.size());
    }

    /** 等级权重只在有候选的等级上归一：日程给了 T2 权重但内容缺 T2 → 候选恒 T1。 */
    @Test
    public void tierWeightsRenormalizeOverAvailableTiers() {
        Scenario scenario = Scenario.load();
        TagIndex tags = scenario.tags();
        CardIndex onlyT1 = CardIndex.build(List.of(
                new CardDefinition(rl("t1_a"), 1, CardClass.B, Set.of(Scenario.TAG_ATTACK),
                        Optional.empty(), List.of(), List.of()),
                new CardDefinition(rl("t1_b"), 1, CardClass.B, Set.of(Scenario.TAG_ATTACK),
                        Optional.empty(), List.of(), List.of())), tags).value();
        PoolIndex pools = PoolIndex.of(onlyT1);
        DrawProfile standardEarly = scenario.profiles().get(Scenario.PROFILE_STANDARD_EARLY);
        Map<Integer, Integer> weights = Map.of(1, 70, 2, 30);

        for (int seed = 0; seed < 300; seed++) {
            DrawResult result = DrawEngine.draw(onlyT1, pools, standardEarly, weights, Set.of(), new Random(seed));
            assertFalse(result.isEmpty());
            for (ResourceLocation id : result.offers()) {
                assertEquals(1, onlyT1.byId().get(id).tier(),
                        "no T2 cards exist, so every offer must be T1 despite T2 weight 30");
            }
        }
    }

    /** 标签份额公式性质：0 ≤ S < cap、S(0)=0、单调不减、半饱和点 S(8)=0.4。 */
    @Test
    public void tagShareIsMonotonicAndCapped() {
        double previous = 0.0;
        for (int w = 0; w <= 1000; w++) {
            double share = WeightModel.tagShare(w);
            assertTrue(share >= 0.0 && share < WeightModel.CAP, "share must be in [0, cap): " + share);
            assertTrue(share >= previous, "share must be monotonic non-decreasing");
            previous = share;
        }
        assertEquals(0.0, WeightModel.tagShare(0));
        assertEquals(0.4, WeightModel.tagShare(8), 1e-9);
        assertTrue(WeightModel.tagShare(1_000_000) < WeightModel.CAP, "asymptotic, never reaches cap");
    }

    /** 首槽必 A（A 入场起）：有 A 候选时第一槽恒 A；A 全被拥有后回退普通槽而不是空槽。 */
    @Test
    public void firstSlotGuaranteesClassAWithFallback() {
        Scenario scenario = Scenario.load();
        DrawProfile standard = scenario.profiles().get(Scenario.PROFILE_STANDARD);
        DrawSchedule.Row row = scenario.schedule().forDraw(6).orElseThrow();

        for (int seed = 0; seed < 300; seed++) {
            DrawResult result = DrawEngine.draw(scenario.cards(), scenario.pools(), standard,
                    row.tierWeights(), Set.of(), new Random(seed));
            assertFalse(result.isEmpty());
            assertEquals(CardClass.A, scenario.cards().byId().get(result.offers().get(0)).cardClass(),
                    "slot 0 must be class A while A candidates exist");
        }

        // 唯二 A 卡全被拥有 → 槽位回退无过滤，首槽给出普通候选（filter-fallback 必须留痕）。
        DrawResult fallback = DrawEngine.draw(scenario.cards(), scenario.pools(), standard,
                row.tierWeights(), Set.of(Scenario.VENOM_EDGE, Scenario.PHOENIX_BREATH), new Random(7));
        assertFalse(fallback.isEmpty());
        assertTrue(fallback.log().stream().anyMatch(l -> l.contains("slot0 filter-fallback")),
                "A filter exhausted -> fallback logged");
    }

    /** 已拥有卡踢出候选；requires 标签谓词未满足的卡不进候选；满足后进候选（v1.1 语义）。 */
    @Test
    public void ownedCardsLeavePoolAndRequiresGate() {
        Scenario scenario = Scenario.load();
        DrawProfile standardEarly = scenario.profiles().get(Scenario.PROFILE_STANDARD_EARLY);
        DrawSchedule.Row row = scenario.schedule().forDraw(3).orElseThrow();

        // 没有任何带 poison 标签的卡 → venom_cascade（requires nextcard:poison）永不出现。
        for (int seed = 0; seed < 500; seed++) {
            DrawResult result = DrawEngine.draw(scenario.cards(), scenario.pools(), standardEarly,
                    row.tierWeights(), Set.of(), new Random(seed));
            assertFalse(result.offers().contains(Scenario.VENOM_CASCADE),
                    "requires not satisfied -> never offered");
        }

        // 拥有任一 poison 标签卡（venom_edge）→ venom_cascade 可出现在候选（带 T5 权重的抽次）。
        DrawProfile standard = scenario.profiles().get(Scenario.PROFILE_STANDARD);
        DrawSchedule.Row lateRow = scenario.schedule().forDraw(10).orElseThrow();
        boolean seen = false;
        for (int seed = 0; seed < 2000 && !seen; seed++) {
            DrawResult result = DrawEngine.draw(scenario.cards(), scenario.pools(), standard,
                    lateRow.tierWeights(), Set.of(Scenario.VENOM_EDGE), new Random(seed));
            assertFalse(result.offers().contains(Scenario.VENOM_EDGE), "owned card must not be offered");
            seen = result.offers().contains(Scenario.VENOM_CASCADE);
        }
        assertTrue(seen, "with a poison-tagged card owned, the mutation card becomes drawable");
    }

    /** 判断标签（v1.1 §5.5）：不建池、不进权重；满足 requires 谓词即放行通用 C 卡。 */
    @Test
    public void judgmentTagGatesButNeverWeights() {
        Scenario scenario = Scenario.load();
        DrawProfile standard = scenario.profiles().get(Scenario.PROFILE_STANDARD);
        DrawSchedule.Row lateRow = scenario.schedule().forDraw(10).orElseThrow();

        // 不建池：counter 是判断标签，(等级，标签) 投影里没有它。
        assertTrue(scenario.pools().cardsIn(3, Scenario.TAG_COUNTER).isEmpty(),
                "judgment tag must not project into pools");

        // 不进权重：venom_edge 有 3 个标签，但 counter 是判断标签 → 权重只数 poison+attack = 2。
        TagWeights withMarker = TagWeights.of(scenario.cards(), Set.of(Scenario.VENOM_EDGE));
        assertEquals(2, withMarker.total(), "judgment tag must not add weight");
        assertTrue(withMarker.weightOf(Scenario.TAG_COUNTER) == 0, "marker tag has no weight entry");

        // 判断：拥有任一带 counter 标签的卡（venom_edge 或 B 卡 twin_fang）→ chain_reaction 可抽。
        for (ResourceLocation carrier : List.of(Scenario.VENOM_EDGE, rl("twin_fang"))) {
            boolean seen = false;
            for (int seed = 0; seed < 2000 && !seen; seed++) {
                DrawResult result = DrawEngine.draw(scenario.cards(), scenario.pools(), standard,
                        lateRow.tierWeights(), Set.of(carrier), new Random(seed));
                seen = result.offers().contains(Scenario.CHAIN_REACTION);
            }
            assertTrue(seen, "generic C card must be drawable once a counter-tagged card is owned: " + carrier);
        }

        // 没有计数家族卡 → chain_reaction 永不出现。
        for (int seed = 0; seed < 500; seed++) {
            DrawResult result = DrawEngine.draw(scenario.cards(), scenario.pools(), standard,
                    lateRow.tierWeights(), Set.of(rl("starter_4")), new Random(seed));
            assertFalse(result.offers().contains(Scenario.CHAIN_REACTION));
        }
    }

    /** 加载校验：requires 必须引用已注册标签；每卡至少一个非判断标签。 */
    @Test
    public void contentValidationRejectsBadRequiresAndMarkerOnlyCards() {
        TagIndex tags = Scenario.load().tags();
        var badRef = new CardDefinition(rl("bad_ref"), 5, CardClass.C, Set.of(Scenario.TAG_POISON),
                Optional.empty(), List.of(rl("no_such_tag")), List.of());
        var markerOnly = new CardDefinition(rl("marker_only"), 5, CardClass.C, Set.of(Scenario.TAG_COUNTER),
                Optional.empty(), List.of(), List.of());
        var ok = new CardDefinition(rl("ok"), 5, CardClass.C, Set.of(Scenario.TAG_POISON),
                Optional.empty(), List.of(Scenario.TAG_POISON), List.of());

        var result = CardIndex.build(List.of(badRef, markerOnly, ok), tags);
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("must reference a registered tag")),
                "unresolvable requires must fail: " + result.errors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("non-judgment tag")),
                "marker-only card must fail: " + result.errors());
        assertFalse(result.errors().stream().anyMatch(e -> e.contains("nextcard:ok")), "valid card must pass");
    }

    /** 首抽：固定五张 starter 全部作为候选返回（5 选 1 由消费方执行）。 */
    @Test
    public void firstDrawOffersFixedStarters() {
        Scenario scenario = Scenario.load();
        DrawResult result = DrawEngine.draw(scenario.cards(), scenario.pools(),
                scenario.profiles().get(Scenario.PROFILE_FIRST), Map.of(), Set.of(), new Random(1));
        assertFalse(result.isEmpty());
        assertEquals(Scenario.STARTERS, result.offers());
    }

    /** 同一次 5 张内部去重（Q9 默认）。 */
    @Test
    public void offersAreDistinctWithinOneDraw() {
        Scenario scenario = Scenario.load();
        DrawProfile standard = scenario.profiles().get(Scenario.PROFILE_STANDARD);
        for (int seed = 0; seed < 500; seed++) {
            DrawSchedule.Row row = scenario.schedule().forDraw(10).orElseThrow();
            DrawResult result = DrawEngine.draw(scenario.cards(), scenario.pools(), standard,
                    row.tierWeights(), Set.of(), new Random(seed));
            assertEquals(new HashSet<>(result.offers()).size(), result.offers().size());
        }
    }

    /** 蒙特卡洛性质（G3）：任意 15 抽路径不撞死局；T5 不早于第 9 抽。 */
    @Test
    public void monteCarloRunSatisfiesScheduleInvariants() {
        var report = com.klze.nextcard.sim.MonteCarlo.run(20260920L, 200);
        assertEquals(0, report.deadEndRuns(), "no run may hit 卡池无卡 with the scenario content");
        assertEquals(0, report.t5BeforeEntryViolations(),
                "T5 must not appear before draw 9; samples: " + String.join(" || ", report.violationDetails()));
        assertTrue(report.tagBranchSlots() > 0, "owned tags must eventually steer draws");
        assertTrue((double) report.tagBranchSlots() / report.totalSlots() < WeightModel.CAP);
    }

    private static ResourceLocation rl(String path) {
        return new ResourceLocation("nextcard", path);
    }
}
