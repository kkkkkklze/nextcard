package com.klze.nextcard.logic;

import com.klze.nextcard.common.combat.CardCombat;
import com.klze.nextcard.core.effect.AttackPipeline;
import com.klze.nextcard.core.effect.DefencePipeline;
import com.klze.nextcard.core.effect.MechanicProfile;
import com.klze.nextcard.core.effect.ModifierClause;
import com.klze.nextcard.core.effect.Settlement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 两段链的接缝：攻方交付 → 守方落地，以及通道名 → 该进哪一段。 */
public class SettlementTest {

    /** 攻方的乘区放大不了守方的减免：这是分两条管线唯一的理由。 */
    @Test
    public void attackZonesNeverAmplifyTheDefendersBooks() {
        AttackPipeline.Input attack = new AttackPipeline.Input(100, 1.0, null, java.util.Map.of(),
                1.0, 0.0, 0.0, 2.0, 0.0, false, 0.0, 0.0, java.util.Set.of());
        DefencePipeline.Options defence = new DefencePipeline.Options(null, List.of(), List.of(),
                List.of(new DefencePipeline.Source("盾墙", 0.5)));
        Settlement.Result result = Settlement.resolve(attack, defence);
        assertEquals(400.0, result.delivery().value(), 1e-9, "交付 = 100 ×(1+1) 全伤 ×2 暴击");
        assertEquals(200.0, result.value(), 1e-9, "守方那 50% 打在 400 上，不是打在 100 上");
        assertEquals(0.5, result.defence().remainingRate(), 1e-9,
                "剩余率相对的是进守方时的那个数");
    }

    /** 留痕是一条链：读的人不用自己对接两个列表。 */
    @Test
    public void traceReadsAsOneChainAcrossBothSides() {
        Settlement.Result result = Settlement.resolve(Settlement.plain(100),
                new DefencePipeline.Options(null, List.of(new DefencePipeline.Source("黄心", 4)),
                        List.of(), List.of()));
        List<String> lines = result.trace();
        assertTrue(lines.get(0).startsWith("攻 "), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("守 护盾吸收")), lines.toString());
        assertTrue(lines.get(lines.size() - 1).startsWith("守 交付"), lines.toString());
        assertTrue(result.summary().contains("交付"), result.summary());
    }

    /** 守方空账也要跑一次：交付痕在，数字不变。 */
    @Test
    public void anEmptyDefenderStillGetsDelivered() {
        Settlement.Result result = Settlement.resolve(Settlement.plain(87.5), null);
        assertEquals(87.5, result.value(), 1e-9);
        assertNotNull(result.defence());
    }

    /**
     * 通道名 → 段的映射（战斗接管里唯一会静默改变数值解释的一步）。
     * 这几条通道由内容侧词表证实存在；暴击、破甲、护盾、穿透刻意未接，见 {@code CardCombat} 的注释。
     */
    @Test
    public void channelsMapOntoThePipelineSideTheyBelongTo() {
        MechanicProfile attack = MechanicProfile.fold(List.of(
                new ModifierClause("channel.all_damage", 1.0, "", 0.0, List.of()),
                new ModifierClause("channel.melee_damage", 0.5, "", 0.0, List.of()),
                new ModifierClause("channel.direction_bonus", 0.2, "", 0.0, List.of())), id -> 0);
        Settlement.Result result = CardCombat.settle(attack, null, 100.0);
        assertNotNull(result);
        assertEquals(360.0, result.value(), 1e-9, "100 ×(1+1) 全伤 ×(1+0.5) 近战 ×(1+0.2) 方向");

        MechanicProfile defence = MechanicProfile.fold(List.of(
                new ModifierClause("channel.damage_reduction", 0.25, "", 0.0, List.of())), id -> 0);
        assertEquals(75.0, CardCombat.settle(null, defence, 100.0).value(), 1e-9,
                "守方减伤走的是同一条链的减免段，不是另开一次乘法");
        assertEquals(270.0, CardCombat.settle(attack, defence, 100.0).value(), 1e-9,
                "两边都在场时先乘后减：360×0.75");

        assertNull(CardCombat.settle(null, null, 100.0), "谁都没有可生效加成时不该参与结算");
        assertNull(CardCombat.defenceOptionsFor(defenceWithNoReduction()), "减伤为 0 不是账");
    }

    private static MechanicProfile defenceWithNoReduction() {
        return new MechanicProfile(java.util.Map.of("channel.move_speed",
                new com.klze.nextcard.core.effect.Mechanics.Folded(0.1, false)));
    }
}
