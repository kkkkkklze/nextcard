package com.klze.nextcard.core.effect;

/**
 * 减速（{@code slow} 动作）的<em>纯算式</em>：卡面写的"减多少"要变成一条属性修正，
 * 而"减 30% 是不是 30%"这条判据必须能在不启动游戏的地方被证明。
 *
 * <p>用 {@code MULTIPLY_TOTAL} 而不是 {@code ADDITION}：原版的移速 debuff 走的就是乘区
 * （{@code MobEffects.MOVEMENT_SLOWDOWN}），用加法会与任何已存在的 buff 互相稀释，
 * 玩家看到的速度就不是卡面写的那个百分比。比值与点值不混这一条与
 * {@link Mechanics#FLAT_CHANNELS} 同律。</p>
 *
 * <p>修正的 UUID 派生不在这里——那是"挂在谁身上、一条还是两条"的事，归
 * {@code TargetStates.idOf}（它同时管减速与护甲穿透两族，见 {@link ArmourPiercing}）。</p>
 */
public final class Slowness {

    private Slowness() {
    }

    /**
     * 比例夹在 0~1。写 1.5 不等于"把速度变成负的"，写 0 或负数也不等于"反过来加速"。
     *
     * <p>这里<em>夹住</em>而不是报错：动作的数值是运行时算出来的（带 {@code per_stack}
     * 之类的一路都可能算出越界值），运行时不该把服务器打断；而加载期的非法写法
     * （负数、字符串）由 {@link Action} 直接拒。</p>
     */
    public static double clamped(double percent) {
        if (!(percent > 0.0)) {
            return 0.0;
        }
        return Math.min(1.0, percent);
    }

    /** 交进属性修正的那个量：减速是负数（乘区算的是 {@code 1 + amount}）。 */
    public static double amount(double percent) {
        return -clamped(percent);
    }

    /** 减完之后还剩多少速度（1.0 = 一点没减）。 */
    public static double remainingFactor(double percent) {
        return 1.0 - clamped(percent);
    }
}
