package com.klze.nextcard.logic;

import com.klze.nextcard.core.effect.AttackPipeline;
import com.klze.nextcard.core.effect.DefencePipeline;
import com.klze.nextcard.core.effect.Settlement;
import com.klze.nextcard.core.effect.ShieldBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守方管线的口径断言：顺序、谁吃了一口、只有否决能归零、穿透跳过要留痕。
 *
 * <p>形状照姊妹工程求仙问道的 {@code DefencePipeline}（顺序写死、无优先级旋钮、免疫带归因）。</p>
 */
public class DefencePipelineTest {

    private static AttackPipeline.Delivery hit(double value) {
        return AttackPipeline.resolve(Settlement.plain(value));
    }

    private static AttackPipeline.Delivery penetratingHit(double value, AttackPipeline.Penetration kind) {
        return AttackPipeline.resolve(new AttackPipeline.Input(value, 1.0, null, Map.of(), 0.0, 0.0, 0.0,
                1.0, 0.0, false, 0.0, 0.0, AttackPipeline.penetration(kind)));
    }

    private static DefencePipeline.Options options(String veto, List<DefencePipeline.Source> shields,
                                                   List<DefencePipeline.Source> flats,
                                                   List<DefencePipeline.Source> ratios) {
        return new DefencePipeline.Options(veto, shields, flats, ratios);
    }

    private static DefencePipeline.Source source(String name, double amount) {
        return new DefencePipeline.Source(name, amount);
    }

    /** 《02》口径：比例减伤是乘法叠加，两个 20% 是 −36% 而不是 −40%。 */
    @Test
    public void ratioReductionsCombineByGradientDescent() {
        DefencePipeline.Result result = DefencePipeline.run(hit(100), options(null, List.of(), List.of(),
                List.of(source("护甲附魔", 0.2), source("稳手", 0.2))));
        assertEquals(64.0, result.landed(), 1e-9);
        assertEquals(0.36, 1.0 - result.remainingRate(), 1e-9, "剩余率要说得出是 0.64");
        assertTrue(result.traceOf(DefencePipeline.Step.RATIO_REDUCTION).toString().contains("护甲附魔"),
                "比例那一步必须点名每个来源: " + result.traces());
    }

    /** 只有第①步能把数打成 0，并且它必须带归因。 */
    @Test
    public void onlyTheVetoStepCanZeroTheHitAndItSaysWhy() {
        DefencePipeline.Result vetoed = DefencePipeline.run(hit(100),
                options("不屈：护甲高于生命上限的 50%", List.of(), List.of(), List.of()));
        assertEquals(0.0, vetoed.landed(), 1e-9);
        assertTrue(vetoed.traceOf(DefencePipeline.Step.VETO).toString().contains("不屈"),
                "否决要能回答是谁拦下的: " + vetoed.traces());

        // 否决之后再没有账可算：护盾一口都不该吃（否则盾被白扣，玩家看到的是"免疫还掉盾"）
        DefencePipeline.Result vetoedWithShield = DefencePipeline.run(hit(100),
                options("免疫", List.of(source("壁障", 50)), List.of(), List.of()));
        assertEquals(0.0, vetoedWithShield.landed(), 1e-9);
        assertEquals(0.0, vetoedWithShield.shieldAbsorbed(), 1e-9, "伤害已经不在了，盾不该扣账");
    }

    /** 护盾是定值、逐来源各吃自己那一份，吃掉多少要交回调用点去扣账。 */
    @Test
    public void shieldsAreFinitePointsConsumedOneSourceAtATime() {
        DefencePipeline.Result result = DefencePipeline.run(hit(30), options(null,
                List.of(source("黄心", 10), source("壁障", 40)), List.of(), List.of()));
        assertEquals(0.0, result.landed(), 1e-9, "两面盾合计 50 ≥ 30，这一口被吃光");
        assertEquals(List.of("黄心", "壁障"),
                result.shieldEaten().stream().map(DefencePipeline.Source::name).toList());
        assertEquals(List.of(10.0, 20.0),
                result.shieldEaten().stream().mapToDouble(DefencePipeline.Source::amount).boxed().toList(),
                "每个来源只吃自己那一份：第二面只剩 40−20，交回的是实际吃掉的量而不是它原本有多少");
    }

    /** 固定减伤先于比例减伤：否则同一个 20% 会随点数变化而改变实际价值。 */
    @Test
    public void flatReductionIsSettledBeforeTheRatioStep() {
        DefencePipeline.Options both = options(null, List.of(), List.of(source("坚壁", 20)),
                List.of(source("盾墙", 0.5)));
        assertEquals(40.0, DefencePipeline.run(hit(100), both).landed(), 1e-9,
                "(100−20)×0.5，不是 100×0.5−20=30，也不是两段相加 30");
    }

    /** 攻方声明"无视减伤"时，③④两步被跳过并留下"为什么没生效"；第①步否决跳不过。 */
    @Test
    public void penetrationSkipsDefenceStepsButNeverTheVeto() {
        AttackPipeline.Delivery execute = penetratingHit(100, AttackPipeline.Penetration.IGNORE_REDUCTION);
        DefencePipeline.Options books = options(null, List.of(), List.of(source("坚壁", 20)),
                List.of(source("盾墙", 0.5)));
        DefencePipeline.Result result = DefencePipeline.run(execute, books);
        assertEquals(100.0, result.landed(), 1e-9);
        assertEquals(2, result.bypassed().size(), "两步都要说得出被穿透: " + result.bypassed());

        DefencePipeline.Result stillVetoed = DefencePipeline.run(execute,
                new DefencePipeline.Options("免疫阶段", books.shieldPoints(), books.flatReductions(),
                        books.ratioReductions()));
        assertEquals(0.0, stillVetoed.landed(), 1e-9, "《00》：血量锁定 / 免疫阶段无法触发");
    }

    /** "无视护盾"只跳过护盾段，减伤照常（横扫的口径）。 */
    @Test
    public void ignoringShieldLeavesTheRestOfTheBooksIntact() {
        DefencePipeline.Result result = DefencePipeline.run(
                penetratingHit(100, AttackPipeline.Penetration.IGNORE_SHIELD),
                options(null, List.of(source("壁障", 50)), List.of(), List.of(source("盾墙", 0.2))));
        assertEquals(80.0, result.landed(), 1e-9, "盾没吃，减伤照算");
        assertEquals(1, result.bypassed().size());
        assertTrue(result.bypassed().get(0).contains("护盾"), result.bypassed().toString());
    }

    /** 「禁止绝对」做成会失败的判据：单个减免来源写不到 100%，也不许伪装成负数。 */
    @Test
    public void noReductionSourceMayReachAbsoluteOrGoNegative() {
        IllegalArgumentException tooMuch = assertThrows(IllegalArgumentException.class,
                () -> options(null, List.of(), List.of(), List.of(source("某张卡", 1.0))));
        assertTrue(tooMuch.getMessage().contains("某张卡"), "报错要点名是哪个来源: " + tooMuch.getMessage());
        assertTrue(tooMuch.getMessage().contains("免疫"), "要指出该走哪条路: " + tooMuch.getMessage());

        assertThrows(IllegalArgumentException.class, () -> options(null, List.of(),
                List.of(source("伪装成负减伤的增伤", -5)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new DefencePipeline.Source("", 10),
                "没有名字的来源归不了因，不许进管线");
    }

    /** 没有来源就没有账：跑过但没改数的步不该占一行。 */
    @Test
    public void stepsThatChangedNothingLeaveNoTrace() {
        DefencePipeline.Result bare = DefencePipeline.run(hit(100));
        assertFalse(bare.mitigates());
        assertEquals(List.of(DefencePipeline.Step.DELIVER),
                bare.traces().stream().map(trace -> trace.step()).toList());
        assertEquals(100.0, bare.landed(), 1e-9, "守方空账时原样交付");
        assertEquals(Set.of(), Set.copyOf(bare.bypassed()), "没声明穿透时不该有跳过记录");
    }

    /**
     * 举盾挡掉多少是 manifest 里那个数（klze 2026-09-30 的裁定：先给个数，内容侧在游戏里改 JSON
     * 试手感）。这三条断言就是"改这个数到底改变了什么"的出处：出厂 1.0 必须与原版<em>逐位相同</em>，
     * 否则这条改动今天就在动所有人的手感。
     */
    @Test
    public void theShieldCancelsExactlyTheFractionTheNumberAsksFor() {
        assertEquals(100.0, ShieldBlock.blockedOf(100.0, 1.0), 1e-9, "1.0 = 原版整个取消");
        assertEquals(0.0, ShieldBlock.remainingOf(100.0, 1.0), 1e-9);
        assertEquals(20.0, ShieldBlock.blockedOf(100.0, 0.2), 1e-9, "0.2 = 只取消两成");
        assertEquals(80.0, ShieldBlock.remainingOf(100.0, 0.2), 1e-9,
                "剩下那 80 要照常走护甲与减免——盾不是减伤通道");
        assertEquals(0.0, ShieldBlock.blockedOf(100.0, 0.0), 1e-9, "0 = 这盾白举");
    }

    /** 越界的数不许变成新机制：负数是"挡出伤害"，大于 1 是"挡出额外的伤害"，都不是这个数的语义。 */
    @Test
    public void aReductionOutsideZeroToOneIsClampedInsteadOfInventingBehavior() {
        assertEquals(0.0, ShieldBlock.clamped(-0.2), 1e-9);
        assertEquals(1.0, ShieldBlock.clamped(1.5), 1e-9);
        assertEquals(0.0, ShieldBlock.clamped(Double.NaN), 1e-9, "NaN 也不能漏进结算");
        assertEquals(0.0, ShieldBlock.blockedOf(0.0, 1.0), 1e-9, "没有进来的伤害就没有可挡的");
        assertEquals(0.0, ShieldBlock.blockedOf(-5.0, 0.5), 1e-9,
                "原版根本不会为空伤害发这个事件，这里也不给它算出个负数");
    }
}
