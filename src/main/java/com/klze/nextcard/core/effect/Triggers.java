package com.klze.nextcard.core.effect;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * 触发执行器：一次事件叫醒<em>哪几张卡的哪几个动作</em>，并把引擎会做的那几种做掉。
 *
 * <p>词表早就冻结了（{@link Action} 十四种、{@link TriggerClause} 的 when/window/uses/every），
 * 消费它们要先有三样齐活：判定（{@link Predicates}）、能记在途次数的账本（{@link CounterStore}）、
 * 世界侧事实（{@code DamageContact}）。今天这三样都有了，所以这个类只做调度，不自建任何状态。</p>
 *
 * <p>三条纪律：</p>
 * <ol>
 *   <li><b>不致命就不碰 {@code lethal_immunity} 的账本</b>。判错方向的代价不对称：把普攻当成致命
 *       会白扣一次 20 秒冷却，玩家真正该活下来的那一下就没免疫了。</li>
 *   <li><b>没有执行器的动作要报出来，不许静默跳过</b>（{@link Result#unsupported()}）。
 *       "解析通过了但引擎不执行"要是被伪装成"已执行"，内容侧得到的反馈就是"这卡没用"，
 *       而真相是词表里还缺一种落地。</li>
 *   <li><b>引擎自有的资源用 {@link #COOLDOWN_PREFIX} 命名空间</b>，内容声明的叠层不许占用
 *       （加载期由 {@code EffectClauses} 拦），否则"壁障"和"免疫冷却"会记在同一本账上。</li>
 * </ol>
 */
public final class Triggers {

    /** 守方挨的这一下。 */
    public static final String DAMAGE_TAKEN = "damage_taken";

    /** 攻方打中的这一下。 */
    public static final String HIT = "hit";

    /** 守方格挡住了、但没卡在窗口里。 */
    public static final String BLOCK_SUCCESS = "block_success";

    /** 守方在窗口内举盾挡下这一下（精准格挡）。 */
    public static final String PARRY_SUCCESS = "parry_success";

    /** 动作：免疫这一发致命伤害。 */
    public static final String LETHAL_IMMUNITY = "lethal_immunity";

    /** 动作：加 / 扣叠层。 */
    public static final String STACKS = "stacks";

    /** 动作：补一次独立伤害（基数 × 系数）。 */
    public static final String DAMAGE = "damage";

    /** 动作：把目标推开。 */
    public static final String KNOCKBACK = "knockback";

    /** 引擎自有资源的命名空间（冷却、在途窗口都归它）。 */
    public static final String COOLDOWN_PREFIX = "trigger.";

    /** 一张卡上的一个触发子句（带上来源卡 id，冷却与归因都要它）。 */
    public record Bound(ResourceLocation cardId, TriggerClause clause) {
    }

    /** 一次兑现：哪张卡、哪个动作、为什么。 */
    public record Firing(ResourceLocation cardId, Action action, String reason) {
    }

    /**
     * 动作可用的<b>基数</b>：由接管点从世界读好交进来（护甲值 / 攻击力 / 这一发的量）。
     *
     * <p>为什么不在执行器里读实体：那样就没法无头断言"以护甲值为基数"到底乘了几遍。
     * {@code const} 走 {@link #of} 里的 1.0——卡面写 {@code coefficient} 就是那个数本身。</p>
     */
    public record Bases(double armor, double attack, double incoming) {

        public static final Bases NONE = new Bases(0.0, 0.0, 0.0);

        public double of(String basis) {
            return switch (basis) {
                case "armor" -> armor;
                case "attack" -> attack;
                case "incoming" -> incoming;
                default -> 1.0;
            };
        }
    }

    /**
     * 要补的一次<b>独立</b>伤害（由接管点去落：找半径内的目标、用自己的 DamageSource）。
     *
     * <p>"独立"指的是<em>另起一发</em>：它会完整再过一次两条管线（攻方乘区照乘、守方减免照算），
     * 这正是《00》"以护甲值为基数的完整乘区"要的形状；它<em>不并进</em>原来那一发的数里，
     * 所以同一个乘区对同一个数字不会生效两遍。谁去保证不递归：接管点认 {@code CardDamageSource}，
     * 自己发出去的命中不再叫醒攻方触发器。</p>
     */
    public record ExtraHit(ResourceLocation cardId, double amount, double radius, String attribution) {
    }

    /** 要把目标推开多少（半径 0 = 只推直接目标）。 */
    public record KnockbackHit(ResourceLocation cardId, double strength, double radius, String attribution) {
    }

    /**
     * 一次事件的全部结果。
     *
     * @param vetoReason  守方管线第①步要用的否决理由（没有任何免疫成立时为 null）
     * @param fired       引擎自己就已经做完的动作（免疫记账、层数增减）
     * @param extraHits   要接管点去落地的独立伤害
     * @param knockbacks  要接管点去落地的击退
     * @param unsupported 解析通过但引擎还不执行的动作——必须让内容侧看得见
     */
    public record Result(@Nullable String vetoReason, List<Firing> fired, List<ExtraHit> extraHits,
                         List<KnockbackHit> knockbacks, List<String> unsupported) {

        public Result {
            fired = List.copyOf(fired);
            extraHits = List.copyOf(extraHits);
            knockbacks = List.copyOf(knockbacks);
            unsupported = List.copyOf(unsupported);
        }

        public static final Result NOTHING = new Result(null, List.of(), List.of(), List.of(), List.of());
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
     * 兑现一次事件。
     *
     * <p>遍历按卡 id 排过序，所以同一现场跑两次得到同一结果（不依赖 map 的遍历顺序）。
     * 同一类动作只兑现一张卡（免疫先到先得），其余卡的次数保留——两张免疫卡轮流用才是玩家
     * 看到的"cd 到了还有救"。</p>
     *
     * <p>{@code uses} 与 {@code cooldown} 的读法（只此一种）：<b>一次兑现放一枚"在途"计数，
     * 各计自己的冷却，在途数达到 {@code uses} 才拦住</b>。{@code uses=1}（首板卡表里唯一的写法）
     * 就是普通的"免疫一次、进 20 秒冷却"；多层充能是同一条规则的形状，不需要另一种实现。</p>
     *
     * @param facts    这一瞬间的事实（判定唯一读的东西；{@code fatal} 之类由接管点换算好）
     * @param declared 卡表声明过的叠层规则（上限与时长的出处，见 {@link EffectHost#declaredStacks()}）
     * @param profile  这个持有者折好的快照；每层映射与上限改写都从这里读
     * @param nowSeconds 当前时刻（秒），与 {@link CounterStore} 同一口径
     */
    public static Result fire(String event, String holder, Facts facts, List<Bound> bound,
                             CounterStore counters, Map<String, StackClause> declared,
                             @Nullable MechanicProfile profile, Bases bases, double nowSeconds) {
        List<Bound> ordered = new ArrayList<>(bound);
        ordered.sort((left, right) -> left.cardId().compareTo(right.cardId()));
        String veto = null;
        List<Firing> fired = new ArrayList<>();
        List<ExtraHit> extraHits = new ArrayList<>();
        List<KnockbackHit> knockbacks = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        for (Bound entry : ordered) {
            TriggerClause clause = entry.clause();
            if (!event.equals(clause.on())) {
                continue;
            }
            Predicate.Verdict gate = Predicates.allHold(clause.when(), facts);
            if (!gate.holds()) {
                continue;
            }
            for (Action action : clause.actions()) {
                switch (action.type()) {
                    case LETHAL_IMMUNITY -> {
                        if (veto == null) {
                            String reason = spendImmunity(holder, entry, action, facts, counters, nowSeconds);
                            if (reason != null) {
                                veto = entry.cardId() + "：" + reason;
                                fired.add(new Firing(entry.cardId(), action, reason));
                            }
                        }
                    }
                    case STACKS -> {
                        String reason = spendStacks(holder, entry, action, counters, declared, profile,
                                nowSeconds);
                        if (reason != null) {
                            fired.add(new Firing(entry.cardId(), action, reason));
                        }
                    }
                    case DAMAGE -> {
                        String skipped = planDamage(entry, action, bases, unsupported, extraHits);
                        if (skipped != null) {
                            fired.add(new Firing(entry.cardId(), action, skipped));
                        }
                    }
                    case KNOCKBACK -> {
                        String reason = planKnockback(entry, action, bases, knockbacks);
                        fired.add(new Firing(entry.cardId(), action, reason));
                    }
                    default -> unsupported.add(entry.cardId() + " 的 " + action.type()
                            + "（词表里有，引擎还没有执行器）");
                }
            }
        }
        return new Result(veto, fired, extraHits, knockbacks, unsupported);
    }

    /** 独立伤害：基数 × 系数。{@code radius_per_stack} 还没有对应叠层，报出来而不是按 0 算。 */
    private static @Nullable String planDamage(Bound entry, Action action, Bases bases,
                                              List<String> unsupported, List<ExtraHit> out) {
        if (action.body().has("radius_per_stack")) {
            unsupported.add(entry.cardId() + " 的 damage.radius_per_stack（半径随哪条叠层涨还没定）");
            return null;
        }
        String basis = action.body().get("basis").getAsString();
        double coefficient = action.number("coefficient", 0.0);
        double amount = bases.of(basis) * coefficient;
        if (!(amount > 0)) {
            // 基数为 0（没穿甲、系数给 0）是合法结果，但要说出来：否则"没伤害"看起来像"没生效"
            unsupported.add(entry.cardId() + " 的 damage：基数 " + basis + " × 系数 " + coefficient + " = 0");
            return null;
        }
        out.add(new ExtraHit(entry.cardId(), amount, Math.max(0.0, action.number("radius", 0.0)),
                "以" + basisText(basis) + "为基数 ×" + coefficient));
        return "补一次 " + rounded(amount) + " 点独立伤害（" + basisText(basis) + " × " + coefficient + "）";
    }

    private static String planKnockback(Bound entry, Action action, Bases bases, List<KnockbackHit> out) {
        double strength = action.number("strength", 0.0);
        double radius = Math.max(0.0, action.number("radius", 0.0));
        out.add(new KnockbackHit(entry.cardId(), strength, radius, "击退 " + strength));
        return "把目标推开 " + strength + "（半径 " + radius + " 格）";
    }

    private static String basisText(String basis) {
        return switch (basis) {
            case "armor" -> "护甲值";
            case "attack" -> "攻击力";
            case "incoming" -> "这一发的量";
            default -> "常数";
        };
    }

    private static String rounded(double value) {
        return String.valueOf(Math.round(value * 100.0) / 100.0);
    }

    /** @return 兑现理由；不成立（不致命 / 冷却在途）时给 null，并且<em>不动账本</em> */
    private static @Nullable String spendImmunity(String holder, Bound entry, Action action, Facts facts,
                                                 CounterStore counters, double nowSeconds) {
        if (!facts.flag("fatal")) {
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
        return "这一发致命；免疫它（每次各计冷却 " + cooldownSeconds + " 秒，最多同时 " + (int) uses + " 次）";
    }

    /** 加层 / 扣层：上限取"声明值 + 本持有者的改写"，只有 {@code ignore_cap} 允许越过去。 */
    private static @Nullable String spendStacks(String holder, Bound entry, Action action,
                                               CounterStore counters, Map<String, StackClause> declared,
                                               @Nullable MechanicProfile profile, double nowSeconds) {
        String id = action.body().has("id") ? action.body().get("id").getAsString() : "";
        StackClause rule = declared.get(id);
        if (rule == null) {
            return null;  // 加载期已拦下未声明的叠层；这里只是防御，绝不"当它存在"记一笔
        }
        double amount = positive(action.number("amount", 1), 1);
        CounterStore.Key key = new CounterStore.Key(holder, id);
        if (action.body().has("consume") && action.body().get("consume").getAsBoolean()) {
            double spent = counters.spend(key, amount);
            return spent <= 0 ? null : "扣「" + id + "」" + (int) spent + " 层，剩 "
                    + (int) counters.amount(key) + " 层";
        }
        double cap = flag(action, "ignore_cap") ? StackClause.UNBOUNDED
                : profile == null ? rule.cap() : profile.stackCap(id, rule.cap());
        counters.gain(key, amount, new CounterStore.Rule(cap, rule.duration(), CounterStore.Expiry.PER_LAYER),
                nowSeconds);
        return "「" + id + "」+" + (int) amount + " 层（上限 " + capText(cap) + "），现 "
                + (int) counters.amount(key) + " 层";
    }

    private static boolean flag(Action action, String field) {
        return action.body().has(field) && action.body().get(field).getAsBoolean();
    }

    private static String capText(double cap) {
        return Double.isInfinite(cap) ? "无上限" : String.valueOf((int) cap);
    }

    private static double positive(double value, double fallback) {
        return value > 0 ? value : fallback;
    }

    /**
     * 守方这一发是不是致命（原版算完护甲之后、扣血之前的口径）。
     * 判定与执行都要读它，两处各写一份迟早会对不上。
     */
    public static boolean isFatal(double health, double incoming) {
        return health - incoming <= 0.0;
    }

    /**
     * 这个持有者当前在途的引擎自有计数（冷却 + 已开的窗口），用于调试命令与测试断言。
     * 读 {@link CounterStore#snapshot}，所以"到点的那部分"天然不算在途，也不需要先 {@code expire}。
     */
    public static double inFlight(CounterStore counters, String holder, double nowSeconds) {
        Map<String, CounterStore.Held> byResource = counters.snapshot(nowSeconds)
                .getOrDefault(holder, Map.of());
        double total = 0.0;
        for (Map.Entry<String, CounterStore.Held> entry : byResource.entrySet()) {
            if (entry.getKey().startsWith(COOLDOWN_PREFIX)) {
                total += entry.getValue().layers();
            }
        }
        return total;
    }

    /** 某个叠层当前层数（测试与调试命令的读法）。 */
    public static double layers(CounterStore counters, String holder, String stackId) {
        return counters.amount(new CounterStore.Key(holder, stackId));
    }
}
