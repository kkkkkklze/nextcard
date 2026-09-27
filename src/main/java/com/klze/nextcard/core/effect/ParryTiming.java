package com.klze.nextcard.core.effect;

/**
 * 盾反的时机判定：这一发是不是<em>精准</em>格挡。
 *
 * <p>窗口长度只由 {@link WindowMath} 算，这里不重复公式；这里只回答"举盾举了多久 vs 窗口多长"
 * 这一比。原版自己有个门槛：举盾不足 5 tick 不算在挡（{@code isBlocking} 里的
 * {@code useDuration - useItemRemaining >= 5}），所以"刚举盾"永远不等于 0 tick——
 * 窗口比 5 tick 还短的话精准格挡在结构上就打不出来，这条要一起算。</p>
 */
public final class ParryTiming {

    /** 原版认"在格挡"的最短持续时间（tick）。 */
    public static final int VANILLA_BLOCK_TICKS = 5;

    private ParryTiming() {
    }

    /**
     * 声明的基础窗口 + 这个持有者折好的延长/乘区/难度。
     *
     * @param declaredBaseSeconds 招式卡 {@code mechanic: parry} 给的 {@code base_window}；
     *                            没有声明就是"不会盾反"，调用方应当先判过再进来
     */
    public static double windowSeconds(double declaredBaseSeconds, MechanicProfile profile) {
        return WindowMath.windowFromProfile(declaredBaseSeconds, profile);
    }

    /** 窗口折算成 tick（与 {@link Cadence} 同一换算）。 */
    public static int windowTicks(double windowSeconds) {
        return Cadence.ticksOf(windowSeconds);
    }

    /**
     * 精准与否：举盾时长落在窗口内就算精准。
     *
     * <p>下限取原版那 5 tick：那之前根本不算在挡，谈不上"精准"。</p>
     */
    public static boolean precise(int ticksBlocking, double windowSeconds) {
        return ticksBlocking >= VANILLA_BLOCK_TICKS && ticksBlocking <= windowTicks(windowSeconds);
    }
}
