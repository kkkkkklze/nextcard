package com.klze.nextcard.logic;

import com.klze.nextcard.common.combat.CardCombat;
import com.klze.nextcard.core.effect.AttackPipeline;
import com.klze.nextcard.core.effect.CritRules;
import com.klze.nextcard.core.effect.MechanicProfile;
import com.klze.nextcard.core.effect.Mechanics;
import com.klze.nextcard.core.effect.Settlement;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 暴击的口径（klze 2026-09-29 定）：初始 5%、初始暴伤 130%、不要原版跳跃暴击、
 * 暴伤无上限、<b>暴击率满了之后溢出的部分让这一发再暴击一次</b>。
 *
 * <p>这里每条都带具体数字，因为"溢出"最容易写错成"夹到 100%"——夹住的话卡面上那些
 * "+20% 暴击率"在接近满时全部变成空话，而且表现是"没变强"，不是"报错"。</p>
 */
public class CritRulesTest {

    private static MechanicProfile critChannels(double chance, double damage) {
        return new MechanicProfile(Map.of(
                "channel.crit_chance", new Mechanics.Folded(chance, false, ""),
                "channel.crit_damage", new Mechanics.Folded(damage, false, "")));
    }

    /** 基线：谁都没写暴击卡时，面板就是 5% / 130%，而且 roll 的边界要说得清。 */
    @Test
    public void theBaselineIsFivePercentAtOnePointThree() {
        assertEquals(0.05, CritRules.chance(null), 1e-9, "初始暴击率 5%");
        assertEquals(1.3, CritRules.multiplier(null), 1e-9, "初始暴伤 130%");
        assertEquals(1, CritRules.critCount(0.05, 0.04), "roll 小于面板就该暴");
        assertEquals(0, CritRules.critCount(0.05, 0.05), "roll 等于面板不算暴（区间是 [0,1)）");
        assertEquals(1.3, CritRules.totalMultiplier(1.3, 1), 1e-9);
        assertEquals(1.0, CritRules.totalMultiplier(1.3, 0), 1e-9, "不暴击这一格就是 1");
    }

    /** 溢出不是夹住：135% = 必定一次 + 35% 再来一次；240% = 必定两次 + 40% 第三次。 */
    @Test
    public void overflowCritsAgainInsteadOfBeingCapped() {
        // 判据是 roll < 小数部分。边界刻意取 0.34 / 0.36 这种"两侧都离得开"的数：
        // 1.35 - 1 在二进制里是 0.35000000000000009，拿 0.35 本身当边界测的是浮点表示，不是口径。
        assertEquals(2, CritRules.critCount(1.35, 0.34), "溢出 35% 里 roll 到 0.34 → 必定那次 + 溢出那次");
        assertEquals(1, CritRules.critCount(1.35, 0.36), "roll 到 0.36 就没溢出那一次，只剩必定的一次");
        assertEquals(3, CritRules.critCount(2.4, 0.39));
        assertEquals(2, CritRules.critCount(2.4, 0.45));
        assertEquals(1.69, CritRules.totalMultiplier(1.3, 2), 1e-9, "两次就是各乘一遍");
        assertEquals(0.0, CritRules.chance(critChannels(-1.0, 0.0)), 1e-9, "负增量最多把面板压到 0");
    }

    /**
     * "必定暴击"存在时，"不判定"不能靠 roll 值表达：面板 ≥ 100% 的那一部分不需要 roll。
     * 所以 {@link CritRules#NO_ROLL} 是显式的一个值，而不是"roll 到了 1.0"。
     */
    @Test
    public void noRollIsNotTheSameAsRollingHigh() {
        assertEquals(1, CritRules.critCount(1.05, 0.99), "面板 105%：概率那次没中，但必定那次照暴");
        assertEquals(0, CritRules.critCount(1.05, CritRules.NO_ROLL), "不判定才是 0 次");
    }

    /** 暴伤不设上限（正本里它是"便宜词条"）；但倍率不会变成负数。 */
    @Test
    public void critDamageHasNoCeilingButNeverGoesNegative() {
        assertEquals(6.3, CritRules.multiplier(critChannels(0.0, 5.0)), 1e-9);
        assertEquals(0.0, CritRules.multiplier(critChannels(0.0, -1.5)), 1e-9,
                "1.3 - 1.5 要夹在 0，不能让一次暴击把伤害变成符号游戏");
    }

    /** 乘区里只有一格"暴击"：管线拿到的是<em>总系数</em>，次数只为留痕。 */
    @Test
    public void thePipelineGetsOneMultiplierAndTheTraceSaysHowMany() {
        AttackPipeline.Input plain = CardCombat.attackInputFor(critChannels(0.0, 0.0), 100.0, null, 0.0);
        assertNotNull(plain);
        assertEquals(1.3, plain.critMultiplier(), 1e-9, "面板 5% + roll 0 → 一次");
        assertEquals(1, plain.crits());

        AttackPipeline.Input overflow = CardCombat.attackInputFor(critChannels(1.0, 0.0), 100.0, null, 0.02);
        assertNotNull(overflow);
        assertEquals(2, overflow.crits(), "5% + 100% = 105% ⇒ 必定一次 + 5% 再来一次");
        assertEquals(1.69, overflow.critMultiplier(), 1e-9);

        Settlement.Result result = CardCombat.settle(critChannels(1.0, 0.0), null, null, null, 100.0, 0.02);
        assertNotNull(result);
        assertEquals(169.0, result.value(), 1e-9, "100 × 1.3 × 1.3");
        assertTrue(result.trace().toString().contains("2 次"),
                "留痕要能说出这是两次暴击: " + result.trace());
    }

    /** 没传 roll 的旧入口一律"不暴击"——否则既有断言会莫名其妙多出 30%。 */
    @Test
    public void theRollFreeEntrypointNeverCrits() {
        Settlement.Result result = CardCombat.settle(critChannels(1.0, 1.0), null, null, null, 100.0);
        assertNotNull(result);
        assertEquals(100.0, result.value(), 1e-9, "纯推演那条默认不暴击");
    }

    /**
     * 一次性武装（"下一次攻击必定暴击"，klze 2026-09-30）是<em>加进面板</em>的那一格，不是替换面板：
     * +100% 与基线 5% 相加得 105%，于是那条溢出规则照旧生效——"必定一次 + 5% 再来一次"。
     *
     * <p>为什么专门断言"那 5% 还活着"：最容易写错的实现是把武装做成"直接令 crits = 1"，那样面板上的
     * 暴伤词条与溢出规则会同时失效，而表现是"必暴的那一发反而没那么痛"，玩家看不出问题。</p>
     */
    @Test
    public void anArmedCritJoinsThePanelInsteadOfReplacingIt() {
        CritRules.Armed armed = new CritRules.Armed(1.0, 0.0);
        assertEquals(1.05, CritRules.chance(null, armed), 1e-9, "基线 5% + 武装 100%");
        assertEquals(1, CritRules.critCount(CritRules.chance(null, armed), 0.36),
                "概率那次没中，必定那次照暴");
        assertEquals(2, CritRules.critCount(CritRules.chance(null, armed), 0.04),
                "面板那 5% 的溢出不能因为武装就失效");
        assertEquals(0, CritRules.critCount(CritRules.chance(null, armed), CritRules.NO_ROLL),
                "不判定这一格时武装也不该造出暴击（纯推演的口径不变）");

        assertEquals(1.7, CritRules.multiplier(critChannels(0.0, 0.1), new CritRules.Armed(0.0, 0.3)), 1e-9,
                "暴伤 130% + 面板 10% + 武装 30%");

        AttackPipeline.Input unarmed = CardCombat.attackInputFor(critChannels(0.0, 0.0), 100.0, null, 0.36);
        assertNotNull(unarmed);
        assertEquals(0, unarmed.crits(), "同一个 roll 在没武装时不该暴");
        AttackPipeline.Input armedHit = CardCombat.attackInputFor(critChannels(0.0, 0.0), 100.0, null,
                0.36, armed);
        assertNotNull(armedHit);
        assertEquals(1, armedHit.crits(), "武装之后同一个 roll 必暴一次");
        Settlement.Result result = CardCombat.settle(critChannels(0.0, 0.0), null, null, null, 100.0, 0.36,
                armed);
        assertNotNull(result);
        assertEquals(130.0, result.value(), 1e-9, "100 × 1.3");
    }

    /**
     * 卡面写"必暴"就是要判定这一格：走"不判定"那条入口的调用点（{@link CardCombat#NEVER_CRITS}）
     * 不能把<em>已经消费掉的</em>武装无声吞掉——账扣了、数没变是最难查的静默。
     * 补进去的只有"必定那一部分"，概率那一次仍然不赌。
     */
    @Test
    public void anArmedTokenIsNotSwallowedByTheRollFreeEntrypoint() {
        assertEquals(1, CritRules.critCount(1.05, CritRules.GUARANTEED_ONLY), "105% ⇒ 必定一次、不赌小数");
        assertEquals(2, CritRules.critCount(2.4, CritRules.GUARANTEED_ONLY), "240% ⇒ 必定两次");
        assertEquals(0, CritRules.critCount(0.05, CritRules.NO_ROLL), "没武装时那条入口依旧整格跳过");

        AttackPipeline.Input input = CardCombat.attackInputFor(critChannels(0.0, 0.0), 100.0, null,
                CardCombat.NEVER_CRITS, new CritRules.Armed(1.0, 0.0));
        assertNotNull(input);
        assertEquals(1, input.crits(), "不判定 + 有武装 ⇒ 至少给必定那一次");
        assertEquals(1.3, input.critMultiplier(), 1e-9);

        AttackPipeline.Input unarmed = CardCombat.attackInputFor(critChannels(0.0, 0.0), 100.0, null,
                CardCombat.NEVER_CRITS);
        assertNotNull(unarmed);
        assertEquals(0, unarmed.crits(), "没武装时这条入口的口径一个字都不动（纯推演与蒙特卡洛靠它）");
    }
}
