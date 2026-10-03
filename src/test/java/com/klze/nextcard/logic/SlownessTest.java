package com.klze.nextcard.logic;

import com.klze.nextcard.core.effect.Slowness;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 减速的算式与修正身份（{@code slow} 动作的数值部分，纯逻辑）。
 *
 * <p>这里能证明的是"卡面写 30% 就是剩 70%"与"一张卡只有一条修正"；
 * 属性真的被挂上/摘掉那半必须在世界里验（GameTest {@code slowTakesTheLegsOffWhoActuallyGotHit}），
 * 见 [[headless-gates-cannot-see-client-code]] 那条同类分界。</p>
 */
public class SlownessTest {

    /** 比例就是比例：0.3 → 修正量 −0.3，速度剩 70%。 */
    @Test
    public void aPercentBecomesTheMatchingMultiplier() {
        assertEquals(-0.3, Slowness.amount(0.3), 1e-9);
        assertEquals(0.7, Slowness.remainingFactor(0.3), 1e-9);
        assertEquals(0.0, Slowness.amount(0.0), 1e-9);
    }

    /** 越界一律夹住，不反着来：1.5 不等于"把速度变成负的"，−0.2 也不等于"加速"。 */
    @Test
    public void outOfRangePercentsAreClampedNotInverted() {
        assertEquals(1.0, Slowness.clamped(1.5), 1e-9, "写超了就是完全不动，不是倒着走");
        assertEquals(-1.0, Slowness.amount(1.5), 1e-9);
        assertEquals(0.0, Slowness.clamped(-0.2), 1e-9);
        assertEquals(1.0, Slowness.remainingFactor(-0.2), 1e-9, "负比例不许变成加速");
        assertEquals(0.0, Slowness.clamped(Double.NaN), 1e-9, "NaN 也不能漏进属性");
    }

    /**
     * 修正的 UUID 由来源键派生：同一张卡永远同一个 id（刷新才摘得掉旧的），
     * 不同来源必须不同（否则两张卡的减速会互相顶掉——那正是"同一 UUID 挂两次"的事故形状）。
     */
    @Test
    public void theModifierIdIsStablePerSourceAndDistinctAcrossSources() {
        assertEquals(Slowness.modifierId("nextcard:grave_bloom"), Slowness.modifierId("nextcard:grave_bloom"),
                "派生必须是纯函数");
        assertNotEquals(Slowness.modifierId("nextcard:grave_bloom"),
                Slowness.modifierId("nextcard:iron_root"));
        UUID id = Slowness.modifierId("nextcard:grave_bloom");
        assertEquals(3, id.version(), "必须是按名字派生的 UUID（v3），随机 UUID 摘不掉：" + id);
    }
}
