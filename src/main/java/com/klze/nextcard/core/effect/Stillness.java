package com.klze.nextcard.core.effect;

import javax.annotation.Nullable;

/**
 * 静止时长（{@code still_seconds} 条件与 {@code moving} 开关<em>唯一</em>的算法）。
 *
 * <p>为什么单独一类、而且不碰 MC：这两件事必须<em>同源</em>。卡面的 {@code {"still_seconds": 2}}
 * 判的是"连续不动了多久"，而 {@link Condition} 里那条叶子的写法是
 * "{@code !moving} 且 时长 ≥ n"——如果 {@code moving} 由速度算、时长由位移算，就会出现
 * "速度为 0 但在漂"或"位置没变但速度非 0"这两种互相矛盾的瞬间（坠落、被推、水里飘都会撞上）。
 * 现在两者都从同一份采样里出来：{@code 位置变了 ⇒ 在动}，{@code 时长从最后一次变化起算}。</p>
 *
 * <p>刻意保留<b>旧坐标</b>而不是每次采样都刷新：极小的位移（水流、船、被推着走）会在几 tick 之后
 * 累计越过阈值，那时才判"动了"并重新开始计时。每 tick 刷新坐标的话，慢速爬行永远算静止。</p>
 *
 * <p>时间口径与 {@link Cadence} 一致：<b>tick 序号换成秒</b>，换算只用
 * {@link Cadence#TICKS_PER_SECOND} 这一格。</p>
 */
public record Stillness(double x, double y, double z, long sinceTick, boolean movedAtLastSample) {

    /**
     * 一 tick 内多小的位移算"没动"（平方距离）。
     *
     * <p>取 1e-6 格² ≈ 每 tick 0.001 格 ≈ 0.02 格/秒：比玩家最慢的受控位移小两个数量级，
     * 又足够大到不会被浮点误差当成移动。</p>
     */
    public static final double EPSILON_SQUARED = 1.0e-6;

    /**
     * 采样一次：位置越过阈值就把这一份账<em>重新开始</em>并记下"这一 tick 动了"，
     * 还在原地就保留旧锚点、并记下"这一 tick 没动"。
     *
     * @param previous 上一份采样（第一次看见这个人时给 null；第一眼不算"刚动过"）
     */
    public static @Nullable Stillness observe(@Nullable Stillness previous, double x, double y, double z,
                                              long nowTick) {
        if (previous == null) {
            return new Stillness(x, y, z, nowTick, false);
        }
        if (previous.movedSince(x, y, z)) {
            return new Stillness(x, y, z, nowTick, true);
        }
        return new Stillness(previous.x, previous.y, previous.z, previous.sinceTick, false);
    }

    /** 到 {@code nowTick} 为止连续静止了多少秒；没采过样或已经动了都是 0（不是"未知"，是"没资格算久"）。 */
    public static double secondsStill(@Nullable Stillness track, double x, double y, double z, long nowTick) {
        if (track == null || track.movedSince(x, y, z)) {
            return 0.0;
        }
        return Math.max(0.0, (nowTick - track.sinceTick) / (double) Cadence.TICKS_PER_SECOND);
    }

    /** 相对这份锚点，给定坐标是否已经<em>越界</em>。 */
    public boolean movedSince(double x, double y, double z) {
        return distanceSquaredTo(x, y, z) > EPSILON_SQUARED;
    }

    /**
     * "在动"的读数：上一次采样结束时动了，<b>或</b>此刻相对锚点已经位移。
     *
     * <p>两个信号都要，是因为采样只在 tick 末尾做一次：只比坐标的话，"这一 tick 动过"会在采样
     * 重新锚定后被抹掉，同一 tick 里稍后才到达的那一次攻击就读成"没动"（{@code {"moving": true}}
     * 那类卡会漏触发一整个 tick）。只取"动了"这一侧的偏差：宁可晚一 tick 承认站定，
     * 不要把移动中的人当成站着。</p>
     */
    public boolean moving(double x, double y, double z) {
        return movedAtLastSample || movedSince(x, y, z);
    }

    /** 当前读数：这一份锚点自己已累计了多久（不再比对坐标，调用方自己确定它还是最新的）。 */
    public double secondsAt(long nowTick) {
        return Math.max(0.0, (nowTick - sinceTick) / (double) Cadence.TICKS_PER_SECOND);
    }

    private double distanceSquaredTo(double ox, double oy, double oz) {
        double dx = ox - x;
        double dy = oy - y;
        double dz = oz - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
