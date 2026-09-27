package com.klze.nextcard.logic;

import com.klze.nextcard.core.effect.AttackPipeline;
import com.klze.nextcard.core.effect.Settlement;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 攻方管线的口径断言（乘区顺序、六选一、方向封顶、尾部加法、穿透声明）。 */
public class AttackPipelineTest {

    /** 留痕必须能定位到步：否则"为什么只打这点伤害"还是只能反推。 */
    @Test
    public void traceNamesEveryZoneThatWasConsidered() {
        AttackPipeline.Input input = new AttackPipeline.Input(100, 2.0, "melee", Map.of("melee", 0.5),
                0.1, 0.0, 0.0, 1.0, 0.0, false, 0.0, 0.0, Set.of());
        AttackPipeline.Delivery delivery = AttackPipeline.resolve(input);
        assertEquals(330.0, delivery.value(), 1e-9, "100×2 系数×1.1 全伤×1.5 近战");
        assertTrue(delivery.traces().stream().anyMatch(line -> line.step()
                == AttackPipeline.Step.ATTACK_CLASS), "分类那一步要点名: " + delivery.traces());
        assertTrue(delivery.traces().stream().anyMatch(line -> line.toString().contains("专属buff 0.0")),
                "没参与的乘区也要留痕为 ×(1+0)，好让人看出它确实被算过: " + delivery.traces());
        assertFalse(delivery.traces().stream().anyMatch(line -> line.step()
                == AttackPipeline.Step.EXTRA_SETTLEMENT),
                "值为 0 的尾部加法不该出现在留痕里: " + delivery.traces());
    }

    /** 六选一不是"事后检查"，而是结构上只可能取到一个分类。 */
    @Test
    public void weaponClassIsExclusiveByConstruction() {
        Map<String, Double> bonuses = Map.of("melee", 0.5, "ranged", 2.0, "magic", 9.0);
        assertEquals(150.0, resolveWith(100, "melee", bonuses).value(), 1e-9, "只有 melee 那一格生效");
        assertEquals(100.0, resolveWith(100, null, bonuses).value(), 1e-9, "不吃任何分类加成");
        assertEquals(100.0, resolveWith(100, "poison", bonuses).value(), 1e-9,
                "分类对不上按 0（《00》：吃不到的乘区按 0）");
    }

    private static AttackPipeline.Delivery resolveWith(double base, String attackClass,
                                                       Map<String, Double> bonuses) {
        return AttackPipeline.resolve(new AttackPipeline.Input(base, 1.0, attackClass, bonuses,
                0.0, 0.0, 0.0, 1.0, 0.0, false, 0.0, 0.0, Set.of()));
    }

    /** 方向增伤封顶 120%，卡面写明"超限"才允许突破。 */
    @Test
    public void directionBonusCapsUnlessTheCardSaysOtherwise() {
        AttackPipeline.Delivery capped = AttackPipeline.resolve(new AttackPipeline.Input(100, 1.0, null,
                Map.of(), 0.0, 0.0, 0.0, 1.0, 3.0, false, 0.0, 0.0, Set.of()));
        assertEquals(220.0, capped.value(), 1e-9, "3.0 被封顶成 1.2 → ×2.2");
        assertTrue(capped.traces().stream().anyMatch(line -> line.toString().contains("封顶")),
                "封顶必须留痕: " + capped.traces());

        AttackPipeline.Delivery exempt = AttackPipeline.resolve(new AttackPipeline.Input(100, 1.0, null,
                Map.of(), 0.0, 0.0, 0.0, 1.0, 3.0, true, 0.0, 0.0, Set.of()));
        assertEquals(400.0, exempt.value(), 1e-9);
    }

    /** "只加本伤害源"与"追加结算"在所有乘法之后，不被任何乘区放大。 */
    @Test
    public void flatAndExtraAdditionsSitOutsideEveryZone() {
        AttackPipeline.Delivery plain = AttackPipeline.resolve(new AttackPipeline.Input(100, 1.0, null,
                Map.of(), 0.0, 0.0, 0.0, 1.0, 0.0, false, 10.0, 5.0, Set.of()));
        AttackPipeline.Delivery amplified = AttackPipeline.resolve(new AttackPipeline.Input(100, 1.0, null,
                Map.of(), 1.0, 0.0, 0.0, 2.0, 0.0, false, 10.0, 5.0, Set.of()));
        assertEquals(115.0, plain.value(), 1e-9);
        assertEquals(415.0, amplified.value(), 1e-9, "全伤×1、暴击×2 只放大前面那 100，不放大 10 与 5");
    }

    @Test
    public void missingBaseIsAnErrorNotAZero() {
        assertThrows(IllegalArgumentException.class, () -> AttackPipeline.resolve(Settlement.plain(0.0)));
        assertThrows(IllegalArgumentException.class, () -> AttackPipeline.resolve(Settlement.plain(-1.0)));
    }

    /** 穿透是攻方的<b>声明</b>：它随交付一起交出去，攻方自己不在此处扣减任何东西。 */
    @Test
    public void penetrationIsADeclarationCarriedByTheDelivery() {
        AttackPipeline.Input none = new AttackPipeline.Input(100, 1.0, null, Map.of(), 0.0, 0.0, 0.0,
                1.0, 0.0, false, 0.0, 0.0, Set.of());
        AttackPipeline.Delivery plain = AttackPipeline.resolve(none);
        assertFalse(plain.penetrates(AttackPipeline.Penetration.IGNORE_SHIELD));

        AttackPipeline.Delivery execute = AttackPipeline.resolve(new AttackPipeline.Input(100, 1.0, null,
                Map.of(), 0.0, 0.0, 0.0, 1.0, 0.0, false, 0.0, 0.0,
                AttackPipeline.penetration(AttackPipeline.Penetration.IGNORE_REDUCTION)));
        assertTrue(execute.penetrates(AttackPipeline.Penetration.IGNORE_REDUCTION));
        assertEquals(plain.value(), execute.value(), 1e-9, "声明本身不改变攻方的数");
        assertTrue(execute.traces().stream().anyMatch(line -> line.toString().contains("无视减伤")),
                "交付那一步要说得出穿透了什么: " + execute.traces());
    }
}
