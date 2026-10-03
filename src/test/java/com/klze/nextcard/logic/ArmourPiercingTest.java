package com.klze.nextcard.logic;

import com.klze.nextcard.core.effect.ArmourPiercing;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 护甲穿透的算式（{@code channel.armor_pierce}）：<b>穿透改的是"原版拿到的护甲值"，
 * 不是伤害数</b>——曲线仍然只由原版算一次。
 *
 * <p>这里能证明的是"该借走几点"；"借了、算完又还回去"那半在世界里验
 * （GameTest {@code armourPierceBorrowsArmourForThisHitOnly}）。分界与
 * {@code SlownessTest} 同一条：无头门看不见 MC 的属性实例。</p>
 */
public class ArmourPiercingTest {

    /** 30% 的 10 点护甲 = 借走 3 点。 */
    @Test
    public void theStripIsThePercentageOfWhatTheTargetActuallyHas() {
        assertEquals(3.0, ArmourPiercing.armorToStrip(10.0, 0.3), 1e-9);
        assertEquals(10.0, ArmourPiercing.armorToStrip(10.0, 1.0), 1e-9, "100% = 整个借走");
        assertEquals(0.0, ArmourPiercing.armorToStrip(10.0, 0.0), 1e-9);
    }

    /** 穿透不是"额外伤害"的另一个通道：目标没有护甲时就借不到东西。 */
    @Test
    public void nothingToBorrowMeansNothingStripped() {
        assertEquals(0.0, ArmourPiercing.armorToStrip(0.0, 0.5), 1e-9, "空甲靶子");
        assertEquals(0.0, ArmourPiercing.armorToStrip(-4.0, 0.5), 1e-9,
                "负的护甲属性不是「可以借出负数」，穿透不该反过来加厚护甲");
    }

    /** 越界一律夹住：150% 不等于把护甲变成负的，负穿透不等于给目标加甲。 */
    @Test
    public void outOfRangeRatiosAreClampedIntoBorrowingOnly() {
        assertEquals(1.0, ArmourPiercing.clamped(1.5), 1e-9);
        assertEquals(10.0, ArmourPiercing.armorToStrip(10.0, 1.5), 1e-9, "夹在整个借走那一档");
        assertEquals(0.0, ArmourPiercing.clamped(-0.3), 1e-9);
        assertEquals(0.0, ArmourPiercing.clamped(Double.NaN), 1e-9, "NaN 不许漏进属性");
    }

    /**
     * 借走的量永不超过目标身上的护甲本身（曲线不接受负护甲，我们也不制造）。
     * 注意比例小于 1 时<em>不会</em>触顶：90% 的 2 点护甲就是 1.8 点，不是"凑个整"。
     */
    @Test
    public void neverBorrowsMoreThanTheTargetHas() {
        assertEquals(1.8, ArmourPiercing.armorToStrip(2.0, 0.9), 1e-9);
        assertEquals(2.0, ArmourPiercing.armorToStrip(2.0, 1.0), 1e-9);
        assertEquals(2.0, ArmourPiercing.armorToStrip(2.0, 9.0), 1e-9,
                "比例再大也只是夹到「整个借走」那一档，不会借出 18 点护甲");
    }
}
