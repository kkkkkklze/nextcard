package com.klze.nextcard.core.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 周期调度（内容声明节奏、引擎负责真的按节奏跑）。
 *
 * <p>裁定的形状是「周期由引擎驱动，但卡自己用 JSON 声明周期」。落到代码就是两件事：
 * 声明侧一个 {@code every} 键（秒，与 {@link StackClause#duration()}、
 * {@link TriggerClause} 的 {@code window.seconds} 与 {@code at} 同一个单位口径），
 * 引擎侧<b>唯一一处</b> 秒→tick 的换算与本类这个"到点没有"的判据。</p>
 *
 * <p>刻意不做的事：① <b>不错峰</b>——把多个持有者的同一周期打散到不同 tick 会改变玩家可见的
 * 触发时机，属于数值/手感决定，不是性能优化可以顺手做掉；② 不补偿掉帧——
 * {@link #dueCount} 按"从起始 tick 起整周期数"算，跳过的周期不追补，
 * 因此服务器卡顿不会把一次触发变成三次。</p>
 */
public final class Cadence {

    /** 20 tick = 1 秒（内容侧「1 刻 = 0.05 秒」就是这一格）。 */
    public static final int TICKS_PER_SECOND = 20;

    private Cadence() {
    }

    /**
     * 唯一的秒→tick 换算。小于一个 tick 的节奏按一个 tick 处理（不许 silently 变成"从不触发"），
     * 非正的周期直接拒绝——静默当成"没写"是 DFU 那类陷阱的源头。
     */
    public static int ticksOf(double seconds) {
        if (!(seconds > 0)) {
            throw new IllegalArgumentException("cadence period must be positive seconds, got " + seconds);
        }
        return Math.max(1, (int) Math.round(seconds * TICKS_PER_SECOND));
    }

    /**
     * 从 {@code onsetTick} 起、到 {@code nowTick} 为止应当已经触发的次数。
     * 用整除而不是累加计时器：不随世界重启漂移，也不因为某一 tick 没被处理而少算。
     */
    public static long dueCount(int periodTicks, long onsetTick, long nowTick) {
        if (nowTick < onsetTick + periodTicks) {
            return 0L;
        }
        return (nowTick - onsetTick) / periodTicks;
    }

    /** 本 tick 是否轮到它触发（与上一 tick 的次数差即为本次要跑的次数，正常恒为 0 或 1）。 */
    public static boolean isDue(int periodTicks, long onsetTick, long nowTick) {
        return dueCount(periodTicks, onsetTick, nowTick) > dueCount(periodTicks, onsetTick, nowTick - 1);
    }

    /**
     * 这一 tick 到点的周期键，按字典序返回。
     *
     * <p>排序不是锦上添花：同一 tick 内多个周期效果若按 {@code Map} 的哈希序执行，
     * 先后会影响"前一个改的槽位是否被后一个读到"，于是同一局面两次跑结果不同。
     * 字典序让这个顺序变成事实的一部分（与 {@code DrawEngine} 里 {@code tagOrder} 同一手法）。</p>
     */
    public static List<String> dueKeys(Map<String, Double> everySeconds, long onsetTick, long nowTick) {
        Map<String, Double> sorted = new TreeMap<>(everySeconds);
        List<String> due = new ArrayList<>();
        for (Map.Entry<String, Double> entry : sorted.entrySet()) {
            if (isDue(ticksOf(entry.getValue()), onsetTick, nowTick)) {
                due.add(entry.getKey());
            }
        }
        return List.copyOf(due);
    }
}
