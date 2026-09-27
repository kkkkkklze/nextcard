package com.klze.nextcard.logic;

import com.klze.nextcard.common.combat.CardCombat;
import com.klze.nextcard.core.effect.DamagePipeline;
import com.klze.nextcard.core.effect.MechanicProfile;
import com.klze.nextcard.core.effect.ModifierClause;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 伤害管线的口径断言（乘区顺序、六选一、方向封顶、减伤叠乘、尾部加法）。 */
public class DamagePipelineTest {

    private static DamagePipeline.Input plain(double base) {
        return new DamagePipeline.Input(base, 1.0, null, Map.of(), 0.0, 0.0, 0.0, 1.0, 0.0,
                false, List.of(), 0.0, 0.0);
    }

    /** 六选一不是"事后检查"，而是结构上只可能取到一个分类。 */
    @Test
    public void weaponClassIsExclusiveByConstruction() {
        Map<String, Double> bonuses = Map.of("melee", 0.5, "ranged", 2.0, "magic", 9.0);
        DamagePipeline.Input melee = new DamagePipeline.Input(100, 1.0, "melee", bonuses,
                0.0, 0.0, 0.0, 1.0, 0.0, false, List.of(), 0.0, 0.0);
        assertEquals(150.0, DamagePipeline.resolve(melee).value(), 1e-9, "只有 melee 那一格生效");

        DamagePipeline.Input unclassified = new DamagePipeline.Input(100, 1.0, null, bonuses,
                0.0, 0.0, 0.0, 1.0, 0.0, false, List.of(), 0.0, 0.0);
        assertEquals(100.0, DamagePipeline.resolve(unclassified).value(), 1e-9, "不吃任何分类加成");

        DamagePipeline.Input otherClass = new DamagePipeline.Input(100, 1.0, "poison", bonuses,
                0.0, 0.0, 0.0, 1.0, 0.0, false, List.of(), 0.0, 0.0);
        assertEquals(100.0, DamagePipeline.resolve(otherClass).value(), 1e-9,
                "分类对不上按 0（《00》：吃不到的乘区按 0）");
    }

    /** 方向增伤封顶 120%，卡面写明"超限"才允许突破。 */
    @Test
    public void directionBonusCapsUnlessTheCardSaysOtherwise() {
        DamagePipeline.Input capped = new DamagePipeline.Input(100, 1.0, null, Map.of(),
                0.0, 0.0, 0.0, 1.0, 3.0, false, List.of(), 0.0, 0.0);
        DamagePipeline.Result result = DamagePipeline.resolve(capped);
        assertEquals(220.0, result.value(), 1e-9, "3.0 被封顶成 1.2 → ×2.2");
        assertTrue(result.trace().stream().anyMatch(line -> line.contains("封顶")),
                "封顶必须留痕: " + result.trace());

        DamagePipeline.Input exempt = new DamagePipeline.Input(100, 1.0, null, Map.of(),
                0.0, 0.0, 0.0, 1.0, 3.0, true, List.of(), 0.0, 0.0);
        assertEquals(400.0, DamagePipeline.resolve(exempt).value(), 1e-9);
    }

    /** 《02》口径：减伤是乘法叠加，两个 20% 是 -36% 而不是 -40%。 */
    @Test
    public void reductionsMultiplyInsteadOfAdding() {
        DamagePipeline.Input two = new DamagePipeline.Input(100, 1.0, null, Map.of(),
                0.0, 0.0, 0.0, 1.0, 0.0, false, List.of(0.2, 0.2), 0.0, 0.0);
        assertEquals(64.0, DamagePipeline.resolve(two).value(), 1e-9);
    }

    /** "只加本伤害源"与"追加结算"在所有乘法之后，不被任何乘区放大。 */
    @Test
    public void flatAndExtraAdditionsSitOutsideEveryZone() {
        DamagePipeline.Input plain = new DamagePipeline.Input(100, 1.0, null, Map.of(),
                0.0, 0.0, 0.0, 1.0, 0.0, false, List.of(), 10.0, 5.0);
        DamagePipeline.Input amplified = new DamagePipeline.Input(100, 1.0, null, Map.of(),
                1.0, 0.0, 0.0, 2.0, 0.0, false, List.of(), 10.0, 5.0);
        assertEquals(115.0, DamagePipeline.resolve(plain).value(), 1e-9);
        assertEquals(415.0, DamagePipeline.resolve(amplified).value(), 1e-9,
                "全伤×1、暴击×2 只放大前面那 100，不放大 10 与 5");
    }

    @Test
    public void missingBaseIsAnErrorNotAZero() {
        assertThrows(IllegalArgumentException.class, () -> DamagePipeline.resolve(plain(0.0)));
        assertThrows(IllegalArgumentException.class, () -> DamagePipeline.resolve(plain(-1.0)));
    }

    /** 留痕必须能定位到步：否则"为什么只打这点伤害"还是只能反推。 */
    @Test
    public void traceNamesEveryStepThatTouchedTheNumber() {
        DamagePipeline.Input input = new DamagePipeline.Input(100, 2.0, "melee", Map.of("melee", 0.5),
                0.1, 0.0, 0.0, 1.0, 0.0, false, List.of(0.25), 0.0, 0.0);
        DamagePipeline.Result result = DamagePipeline.resolve(input);
        assertEquals(247.5, result.value(), 1e-9, "100×2×1.1×1.5×0.75");
        assertTrue(result.trace().stream().anyMatch(line -> line.contains("武器分类")), result.trace().toString());
        assertTrue(result.trace().stream().anyMatch(line -> line.contains("减伤#0")), result.trace().toString());
        assertTrue(result.trace().stream().anyMatch(line -> line.contains("专属buff 0.0")),
                "没参与的乘区也要留痕为 ×(1+0)，好让人看出它确实被算过: " + result.trace());
        assertFalse(result.trace().stream().anyMatch(line -> line.contains("追加结算")),
                "值为 0 的尾部加法不该出现在留痕里: " + result.trace());
    }

    /**
     * 通道名 → 乘区的映射（战斗接管里唯一会静默改变数值解释的一步）。
     * 这几条通道由内容侧词表证实存在；暴击与破甲刻意未接，见 {@code CardCombat} 的注释。
     */
    @Test
    public void channelsMapOntoTheZonesTheyBelongTo() {
        MechanicProfile attack = MechanicProfile.fold(List.of(
                new ModifierClause("channel.all_damage", 1.0, "", 0.0, List.of()),
                new ModifierClause("channel.melee_damage", 0.5, "", 0.0, List.of()),
                new ModifierClause("channel.direction_bonus", 0.2, "", 0.0, List.of())), id -> 0);
        DamagePipeline.Input input = CardCombat.inputFor(attack, null, 100.0);
        assertEquals(360.0, DamagePipeline.resolve(input).value(), 1e-9,
                "100 ×(1+1) 全伤 ×(1+0.5) 近战 ×(1+0.2) 方向");

        assertTrue(CardCombat.inputFor(null, null, 100.0) == null, "谁都没有可生效加成时不该参与结算");
    }

    /** 守方减伤走的是同一条管线的减伤段，不是另开一次乘法。 */
    @Test
    public void defenderReductionGoesThroughTheSameZone() {
        MechanicProfile defence = MechanicProfile.fold(List.of(
                new ModifierClause("channel.damage_reduction", 0.25, "", 0.0, List.of())), id -> 0);
        DamagePipeline.Input input = CardCombat.inputFor(null, defence, 100.0);
        assertEquals(75.0, DamagePipeline.resolve(input).value(), 1e-9);
    }
}
