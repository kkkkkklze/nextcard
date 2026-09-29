package com.klze.nextcard.logic;

import com.klze.nextcard.core.effect.Stillness;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 静止时长：{@code moving} 与 {@code still_seconds} 必须同源，而且慢速位移不能被当成静止。
 *
 * <p>这条门不为"好看"存在。卡面写 {@code {"still_seconds": 2}}（站定两秒才怎样）时，
 * 若 {@code moving} 由速度算、时长由位移算，坠落与被推的那几瞬间两者会互相矛盾；
 * 而每 tick 都刷新坐标的实现，会让"顺着水流慢慢漂"永远算站定——两种错都只能靠断言钉住。</p>
 */
public class StillnessTest {

    /** 站着不动：25 tick 就是 1.25 秒；第一眼不该算"已经站了很久"。 */
    @Test
    public void standingStillAccumulatesSeconds() {
        Stillness track = Stillness.observe(null, 10.0, 64.0, 20.0, 7L);
        assertEquals(0.0, Stillness.secondsStill(track, 10.0, 64.0, 20.0, 7L), 1e-9,
                "采样那一刻的时长是 0");
        assertEquals(1.25, Stillness.secondsStill(track, 10.0, 64.0, 20.0, 32L), 1e-9,
                "25 tick = 1.25 秒（换算只认 20 tick/秒这一格）");
        assertFalse(track.movedSince(10.0, 64.0, 20.0), "同一坐标不该判成在动");
    }

    /** 没采过样 = 无从谈起：时长给 0（不是"很久"），方向是"卡不触发"而不是"卡错触发"。 */
    @Test
    public void unknownReadingIsZeroNotInfinity() {
        assertEquals(0.0, Stillness.secondsStill(null, 1.0, 2.0, 3.0, 999L), 1e-9);
    }

    /** 位置一变就重新开始计时，而且"动了"与"时长为 0"是同一件事的两面。 */
    @Test
    public void movingResetsTheClock() {
        Stillness track = Stillness.observe(null, 0.0, 0.0, 0.0, 0L);
        Stillness stood = Stillness.observe(track, 0.0, 0.0, 0.0, 20L);
        assertEquals(0L, stood.sinceTick(), "没动的时候不该把起点刷新到 20");
        assertEquals(1.0, stood.secondsAt(20L), 1e-9, "起点没动，时长按起点算 = 1 秒");

        assertTrue(stood.movedSince(0.0, 3.0, 0.0), "三个方块就是动了");
        assertEquals(0.0, Stillness.secondsStill(stood, 0.0, 3.0, 0.0, 40L), 1e-9, "动了就没有时长可言");

        Stillness moved = Stillness.observe(stood, 0.0, 3.0, 0.0, 41L);
        assertEquals(41L, moved.sinceTick(), "越界那一刻才重新起算");
        assertEquals(0.0, Stillness.secondsStill(moved, 0.0, 3.0, 0.0, 41L), 1e-9);
        assertEquals(0.5, Stillness.secondsStill(moved, 0.0, 3.0, 0.0, 51L), 1e-9, "之后重新开始累计");
    }

    /**
     * 每 tick 只挪一点点（水流、被推着走、船上）必须在有限 tick 内被判成"在动"。
     *
     * <p>{@link Stillness#observe} 保留<b>旧坐标</b>正是为这一条：逐 tick 刷新坐标的话，
     * 单步永远越不过阈值，漂移会被永久当成站定。</p>
     */
    @Test
    public void creepingMovementEventuallyCountsAsMoving() {
        double step = Stillness.EPSILON_SQUARED * 0.9;   // 阈值是平方距离，单步刻意越不过去
        Stillness track = Stillness.observe(null, 0.0, 0.0, 0.0, 0L);
        long anchoredAt = track.sinceTick();
        double travelled = 0.0;
        int ticks = 0;
        while (track.sinceTick() == anchoredAt && ticks < 10_000) {
            travelled += step;
            ticks++;
            track = Stillness.observe(track, travelled, 0.0, 0.0, ticks);
        }
        assertTrue(ticks < 10_000, "慢速爬行要在有限 tick 内被判成移动，否则「站定」会被漂移骗过");
        assertTrue(ticks > 1, "单步必须越不过阈值，否则等于没设阈值");
        assertEquals((long) ticks, track.sinceTick(), "越界那一刻才重新起算");
        assertEquals(0.0, Stillness.secondsStill(track, travelled, 0.0, 0.0, ticks), 1e-9,
                "重新锚定之后时长从 0 再起");
    }

    /**
     * 动了之后，"在动"这个读数要活过把它消费掉的那一次采样。
     *
     * <p>采样只做一次（tick 末尾），采完锚点就跟着新坐标走了；如果 {@code moving} 只比坐标，
     * 同一 tick 里稍后才到达的那一次命中就会读成"没动"——{@code {"moving": true}} 那类卡会整 tick 漏触发。
     * 所以读数取"上次采样动了 或 此刻位移越界"两个信号。</p>
     */
    @Test
    public void movementFlagSurvivesTheSampleThatReanchorsIt() {
        Stillness track = Stillness.observe(null, 0.0, 0.0, 0.0, 0L);
        assertFalse(track.moving(0.0, 0.0, 0.0), "第一眼不该算在动");

        Stillness moved = Stillness.observe(track, 4.0, 0.0, 0.0, 1L);
        assertTrue(moved.moving(4.0, 0.0, 0.0), "刚把锚点搬到新坐标，这一 tick 仍要读成在动");
        assertEquals(0.0, Stillness.secondsStill(moved, 4.0, 0.0, 0.0, 1L), 1e-9, "动了就没有时长");

        Stillness stoodOnce = Stillness.observe(moved, 4.0, 0.0, 0.0, 2L);
        assertFalse(stoodOnce.moving(4.0, 0.0, 0.0), "下一 tick 没动才转成「没在动」");
        assertEquals(0.1, stoodOnce.secondsAt(3L), 1e-9,
                "时长从动了的那一刻（tick 1）起算：到 tick 3 是 2 tick = 0.1 秒");
    }

    /** 阈值口径要说得出数：单步刚好不超过、超一点就判动。 */
    @Test
    public void epsilonIsASquaredDistanceNotALength() {
        Stillness track = Stillness.observe(null, 0.0, 0.0, 0.0, 0L);
        double justUnder = Math.sqrt(Stillness.EPSILON_SQUARED) * 0.99;
        double justOver = Math.sqrt(Stillness.EPSILON_SQUARED) * 1.01;
        assertFalse(track.movedSince(justUnder, 0.0, 0.0), "单轴刚好在阈值内算没动");
        assertTrue(track.movedSince(justOver, 0.0, 0.0), "刚好越界算动了");
    }
}
