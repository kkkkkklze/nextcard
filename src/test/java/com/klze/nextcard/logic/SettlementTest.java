package com.klze.nextcard.logic;

import com.klze.nextcard.common.combat.CardCombat;
import com.klze.nextcard.core.effect.AttackPipeline;
import com.klze.nextcard.core.effect.DefencePipeline;
import com.klze.nextcard.core.effect.Facts;
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
        Settlement.Result result = CardCombat.settle(attack, null, null, 100.0);
        assertNotNull(result);
        assertEquals(360.0, result.value(), 1e-9, "100 ×(1+1) 全伤 ×(1+0.5) 近战 ×(1+0.2) 方向");

        MechanicProfile defence = MechanicProfile.fold(List.of(
                new ModifierClause("channel.damage_reduction", 0.25, "", 0.0, List.of())), id -> 0);
        assertEquals(75.0, CardCombat.settle(null, defence, null, 100.0).value(), 1e-9,
                "守方减伤走的是同一条链的减免段，不是另开一次乘法");
        assertEquals(270.0, CardCombat.settle(attack, defence, null, 100.0).value(), 1e-9,
                "两边都在场时先乘后减：360×0.75");

        assertNull(CardCombat.settle(null, null, null, 100.0), "谁都没有可生效加成时不该参与结算");
        assertNull(CardCombat.defenceOptionsFor(defenceWithNoReduction(), null), "减伤为 0 且没否决时不该建账");
    }

    /**
     * 方向增伤是<em>条件</em>乘区：卡面写了那个值，接触不成立时也不能白给
     * （《00》背刺＝"从目标背后 120° 打出的攻击"）。
     */
    @Test
    public void directionBonusIsGatedByTheActualContact() {
        MechanicProfile stab = MechanicProfile.fold(List.of(
                new ModifierClause("channel.direction_bonus", 0.3, "", 0.0, List.of())), id -> 0);
        Facts behind = Facts.builder().angleOffFront(175.0).build();
        Facts inFront = Facts.builder().angleOffFront(10.0).build();

        assertEquals(130.0, CardCombat.settle(stab, behind, null, null, 100.0).value(), 1e-9, "背后 5° 吃到 +30%");
        Settlement.Result front = CardCombat.settle(stab, inFront, null, null, 100.0);
        assertEquals(100.0, front.value(), 1e-9, "正面一分不吃");
        assertTrue(front.trace().toString().contains("未生效"),
                "没吃到也要留痕，否则看起来就像卡面没写: " + front.trace());
        // 传 null 是"调用方没有接触信息可给"（纯数值推演），不是世界里那一发：世界路径里
        // 出手的不是活体时压根没有攻方快照，这一格自然是 0，见 DamageContact.attackView
        assertEquals(130.0, CardCombat.settle(stab, null, null, null, 100.0).value(), 1e-9,
                "没有接触事实时按卡面写的算，不额外吞掉加成");
        assertEquals(130.0, CardCombat.settle(stab, Facts.builder().angleOffFront(120.1).build(),
                null, null, 100.0).value(), 1e-9, "离正面 120.1° = 离背面中线 59.9°，还在背后 60° 半角内");
        assertEquals(100.0, CardCombat.settle(stab, Facts.builder().angleOffFront(119.9).build(),
                null, null, 100.0).value(), 1e-9, "差 0.2° 就出扇区");
    }

    /**
     * 执行器给的否决理由要一路走到守方第①步并留在那里——这条接缝就是"免疫"能被回答出
     * "是谁拦下的"的全部依赖链：{@code Triggers → Predicate 的理由 → DefencePipeline 第①步}。
     */
    @Test
    public void aVetoReasonFromTheExecutorZeroesTheHitAndSaysSo() {
        Settlement.Result result = CardCombat.settle(null, null,
                "nextcard:last_stand：这一发致命；免疫这一发", 100.0);
        assertNotNull(result);
        assertEquals(0.0, result.value(), 1e-9);
        assertTrue(result.trace().toString().contains("免疫/否决"), result.trace().toString());
        assertTrue(result.trace().toString().contains("last_stand"), "归因要指到那张卡: " + result.trace());
    }

    private static MechanicProfile defenceWithNoReduction() {
        return new MechanicProfile(java.util.Map.of("channel.move_speed",
                new com.klze.nextcard.core.effect.Mechanics.Folded(0.1, false)));
    }
}
