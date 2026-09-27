package com.klze.nextcard.core.effect;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * 触发执行器：一次事件叫醒<em>哪几张卡的哪几个动作</em>。
 *
 * <p>词表早就冻结了（{@link Action} 十四种、{@link TriggerClause} 的 when/window/uses/every），
 * 但到今天才有人来消费它们——因为消费之前先要有判定（{@link Predicates}）和一个能记冷却的账本
 * （{@link CounterStore}）。这个类刻意做小：<b>只实现一种动作</b>（{@code lethal_immunity}），
 * 因为它是唯一"不需要新造世界对象就能在世界里验"的一种：判致命 → 否决这一发 → 记冷却，
 * 三段全在已有的层里（{@link Predicate} 出理由、{@link DefencePipeline} 第①步吃理由、
 * {@link CounterStore} 记次数）。</p>
 *
 * <p>两条纪律：</p>
 * <ul>
 *   <li><b>不致命就不碰账本</b>。否则一次普通挨打就把 20 秒冷却白扣掉，玩家看到的是
 *       "真被打死时没免疫"——那是最难查的一种坏。</li>
 *   <li><b>引擎自有的资源用 {@link #COOLDOWN_PREFIX} 命名空间</b>，内容声明的叠层不许用这个前缀
 *       （加载期由 {@code EffectClauses} 拦），否则"壁障"和"免疫冷却"会记在同一本账上。</li>
 * </ul>
 */
public final class Triggers {

    /** 事件名：守方挨的这一下（致命免疫挂在它上面）。 */
    public static final String DAMAGE_TAKEN = "damage_taken";

    /** 动作类型：免疫这一发致命伤害。 */
    public static final String LETHAL_IMMUNITY = "lethal_immunity";

    /** 引擎自有资源的命名空间（冷却、在途窗口都归它）。 */
    public static final String COOLDOWN_PREFIX = "trigger.";

    /** 一张卡上的一个触发子句（带上来源卡 id，冷却与归因都要它）。 */
    public record Bound(ResourceLocation cardId, TriggerClause clause) {
    }

    /** 一次兑现：哪张卡、哪个动作、为什么（这句"为什么"直接进守方管线的否决归因）。 */
    public record Firing(ResourceLocation cardId, Action action, String reason) {
    }

    private Triggers() {
    }

    /** 引擎自有的账本键（持有者 + 卡 + 动作三段，两张卡的冷却绝不共享一份账）。 */
    public static CounterStore.Key cooldown(String holder, ResourceLocation cardId, Action action) {
        return new CounterStore.Key(holder, COOLDOWN_PREFIX + cardId + "." + action.type());
    }

    /** 内容侧不许占用引擎命名空间（加载期校验）。 */
    public static boolean isReservedStackId(String stackId) {
        return stackId.startsWith(COOLDOWN_PREFIX);
    }

    /**
     * 兑现「致命伤害免疫」：这一发确实致命、且还有没用掉的免疫次数时，返回该做什么。
     *
     * <p>一次只兑现一张卡（先到先得，按卡 id 排序保证同一现场同一结果），其余卡的次数保留——
     * 两张免疫卡轮流用才是玩家看到的"cd 到了还有救"。副作用只有记一次冷却。</p>
     *
     * <p>{@code uses} 与 {@code cooldown} 的读法（只此一种）：<b>一次兑现放一枚"在途"计数，各计自己的
     * 冷却，在途数达到 {@code uses} 才拦住</b>。{@code uses=1}（首板卡表里唯一的写法）就是普通的
     * "免疫一次、进 20 秒冷却"；多层充能型（"存 2 次，每次用完各计 30 秒"）是同一条规则的形状，
     * 不需要另一种实现。要换读法请先在卡表里点名，别在这里加第二种解释。</p>
     *
     * @param facts 守方视角的事实（{@code fatal} 与自己的血量线由接管点换算好）
     * @param nowSeconds 当前时刻（秒），与 {@link CounterStore} 同一口径
     */
    public static @Nullable Firing lethalImmunity(String holder, Facts facts, List<Bound> bound,
                                                 CounterStore counters, double nowSeconds) {
        if (!facts.flag("fatal")) {
            return null;
        }
        List<Bound> ordered = new ArrayList<>(bound);
        ordered.sort((left, right) -> left.cardId().compareTo(right.cardId()));
        for (Bound entry : ordered) {
            TriggerClause clause = entry.clause();
            if (!DAMAGE_TAKEN.equals(clause.on())) {
                continue;
            }
            for (Action action : clause.actions()) {
                if (!LETHAL_IMMUNITY.equals(action.type())) {
                    continue;
                }
                Firing blocked = checkAndSpend(holder, entry, action, facts, counters, nowSeconds);
                if (blocked != null) {
                    return blocked;
                }
            }
        }
        return null;
    }

    private static @Nullable Firing checkAndSpend(String holder, Bound entry, Action action, Facts facts,
                                                 CounterStore counters, double nowSeconds) {
        Predicate.Verdict gate = Predicates.allHold(entry.clause().when(), facts);
        if (!gate.holds()) {
            return null;
        }
        CounterStore.Key key = cooldown(holder, entry.cardId(), action);
        counters.expire(key, nowSeconds);
        double uses = positive(action.number("uses", 1), 1);
        if (counters.amount(key) >= uses) {
            return null;
        }
        double cooldownSeconds = Math.max(0.0, action.number("cooldown", 0.0));
        counters.gain(key, 1, new CounterStore.Rule(uses, cooldownSeconds, CounterStore.Expiry.PER_LAYER),
                nowSeconds);
        return new Firing(entry.cardId(), action, gate.reason() + "；免疫这一发（每次各计冷却 "
                + cooldownSeconds + " 秒，最多同时 " + (int) uses + " 次）");
    }

    private static double positive(double value, double fallback) {
        return value > 0 ? value : fallback;
    }

    /**
     * 这个持有者当前在途的引擎自有计数（冷却 + 已开的窗口），用于调试命令与测试断言。
     *
     * <p>读的是 {@link CounterStore#snapshot}，所以"到点的那部分"天然不算在途；不需要先 {@code expire}。
     * 没有别的持有者的账混进来——按持有者取，不扫全表。</p>
     */
    public static double inFlight(CounterStore counters, String holder, double nowSeconds) {
        Map<String, CounterStore.Held> byResource = counters.snapshot(nowSeconds).get(holder);
        if (byResource == null) {
            return 0.0;
        }
        double total = 0.0;
        for (Map.Entry<String, CounterStore.Held> entry : byResource.entrySet()) {
            if (entry.getKey().startsWith(COOLDOWN_PREFIX)) {
                total += entry.getValue().layers();
            }
        }
        return total;
    }

    /**
     * 守方这一发是不是致命（原版算完护甲之后、扣血之前的口径）。
     * 放在这里是因为它同时被 {@link Facts} 的开关和免疫动作读——两处各写一份就会出现
     * "判定认为致命、执行器认为不致命"的分叉。
     */
    public static boolean isFatal(double health, double incoming) {
        return health - incoming <= 0.0;
    }
}
