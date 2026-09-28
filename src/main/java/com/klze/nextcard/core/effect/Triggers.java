package com.klze.nextcard.core.effect;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /** 攻方出手的这一下（{@link #HIT} 的另一半：出手看动作，命中看结果，挥空之外的废刀也算）。 */
    public static final String ATTACK = "attack";

    /** 攻方打中的这一下。 */
    public static final String HIT = "hit";

    /** 攻方这一发<em>真的</em>造成了伤害（结算后不为 0；基数装的是最终值，不是进管线的那个数）。 */
    public static final String DAMAGE_DEALT = "damage_dealt";

    /** 攻方把对面打死了。 */
    public static final String KILL = "kill";

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

    /** 动作：把这发的部分反弹给打我的那位。 */
    public static final String REFLECT = "reflect";

    /** 动作：接下来 N 秒内的格挡一律按精准结算。 */
    public static final String FORCE_PARRY = "force_parry";

    /** 独立伤害打给谁。 */
    public enum Target {
        /** 以持卡人为圆心、按半径找活物（冲击波）。 */
        AROUND_OWNER,
        /** 就打我的那一位（反弹）。 */
        ATTACKER
    }

    /** 引擎自有资源的命名空间（冷却、在途窗口都归它）。 */
    public static final String COOLDOWN_PREFIX = "trigger.";

    /** 事件累计账（{@code count} 条件读它）。也在 {@code trigger.} 命名空间内，内容声明的叠层进不来。 */
    public static final String COUNT_PREFIX = COOLDOWN_PREFIX + "count.";

    /** 轮转指针账（{@code sequence} 的 {@code cursor} 读它）。 */
    public static final String CURSOR_PREFIX = COOLDOWN_PREFIX + "cursor.";

    /**
     * 计数与指针用的那条规则：<b>无上限、不过期</b>。它们不是"层数"，是"次数"——
     * 上限由条件自己写（{@code at_least}），到期由"本局"这个边界管（见 {@link #fire} 的注释）。
     */
    private static final CounterStore.Rule COUNTING = new CounterStore.Rule(
            Double.POSITIVE_INFINITY, 0.0, CounterStore.Expiry.PER_LAYER);

    /** 一个计数键（事件名或指针 id 都走同一个形状，方便测试读）。 */
    public static CounterStore.Key countKey(String holder, String prefix, String name) {
        return new CounterStore.Key(holder, prefix + name);
    }

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
    public record ExtraHit(ResourceLocation cardId, double amount, double radius, Target target,
                           String attribution) {
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
        // 事件计数把<em>这一次</em>也算进去再加：第 N 次派动读到的就是 N，所以
        // {@code at_least: 3} 从第三次开始成立，而不是等到第四次。
        // "本局"的边界＝服务端进程里这本账活着的时候（与周期起点同口径，世界重启重新起算、不写存档）。
        counters.gain(countKey(holder, COUNT_PREFIX, event), 1, COUNTING, nowSeconds);
        Facts viewed = withLedger(holder, ordered, counters, facts);
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
            Predicate.Verdict gate = Predicates.allHold(clause.when(), viewed);
            if (!gate.holds()) {
                continue;
            }
            advanceCursors(holder, clause, counters, nowSeconds);
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
                        String reason = planKnockback(entry, action, knockbacks);
                        fired.add(new Firing(entry.cardId(), action, reason));
                    }
                    case REFLECT -> {
                        String reason = planReflect(entry, action, bases, unsupported, extraHits);
                        if (reason != null) {
                            fired.add(new Firing(entry.cardId(), action, reason));
                        }
                    }
                    case FORCE_PARRY -> {
                        String reason = spendForceParry(holder, entry, action, counters, nowSeconds);
                        if (reason != null) {
                            fired.add(new Firing(entry.cardId(), action, reason));
                        }
                    }
                    default -> unsupported.add(entry.cardId() + " 的 " + action.type()
                            + "（词表里有，引擎还没有执行器）");
                }
            }
        }
        return new Result(veto, fired, extraHits, knockbacks, unsupported);
    }

    /**
     * 把这次要评的子句<em>点名</em>的那几笔账贴进快照：层数、事件累计、轮转指针。
     *
     * <p>只读被点到的键、不整本抄账本：账本会随一局游戏一直长（一次命中一笔），而一张卡的条件树
     * 只有几个节点。判定本身仍然只读 {@link Facts}，这条贴的动作是它和真实账之间唯一的接缝。</p>
     */
    private static Facts withLedger(String holder, List<Bound> bound, CounterStore counters, Facts facts) {
        Predicate.Refs refs = refsOf(bound);
        if (refs.isEmpty()) {
            return facts;
        }
        Map<String, Integer> layers = new LinkedHashMap<>();
        for (String id : refs.stacks()) {
            layers.put(id, (int) counters.amount(new CounterStore.Key(holder, id)));
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String name : refs.events()) {
            counts.put(name, (int) counters.amount(countKey(holder, COUNT_PREFIX, name)));
        }
        for (String id : refs.cursors()) {
            counts.put(id, (int) counters.amount(countKey(holder, CURSOR_PREFIX, id)));
        }
        return facts.withLedger(layers, counts);
    }

    /** 一批子句点名的资源总和（同一张卡上写几遍同一个 id 也只读一次）。 */
    private static Predicate.Refs refsOf(List<Bound> bound) {
        Predicate.Refs refs = new Predicate.Refs();
        for (Bound entry : bound) {
            for (Predicate leaf : entry.clause().when()) {
                leaf.collectRefs(refs);
            }
        }
        return refs;
    }

    /**
     * 整条门槛过了，才把这张卡上写着的轮转指针各推进一格。
     *
     * <p>读法见 {@link Combinators.Sequence}：走完这一步才进下一步，走不完就一直停在这里。
     * 推进发生在<em>本次评测之后</em>，所以第 N 步读到的指针是 N−1 之后的值、判的也是第 N 步自己。</p>
     */
    private static void advanceCursors(String holder, TriggerClause clause, CounterStore counters,
                                       double nowSeconds) {
        Predicate.Refs refs = new Predicate.Refs();
        for (Predicate leaf : clause.when()) {
            leaf.collectRefs(refs);
        }
        for (String id : refs.cursors()) {
            counters.gain(countKey(holder, CURSOR_PREFIX, id), 1, COUNTING, nowSeconds);
        }
    }

    /** 某个事件本局累计次数（调试命令与测试的读法）。 */
    public static int times(CounterStore counters, String holder, String event) {
        return (int) counters.amount(countKey(holder, COUNT_PREFIX, event));
    }

    /** 某个轮转指针本局走到第几步（同上）。 */
    public static int cursor(CounterStore counters, String holder, String cursorId) {
        return (int) counters.amount(countKey(holder, CURSOR_PREFIX, cursorId));
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
                Target.AROUND_OWNER, "以" + basisText(basis) + "为基数 ×" + coefficient));
        return "补一次 " + rounded(amount) + " 点独立伤害（" + basisText(basis) + " × " + coefficient + "）";
    }

    /**
     * 反弹：把这一发的一个比例<em>还给打我的那位</em>。
     *
     * <p>基数默认是 {@code incoming}（"反弹该次伤害的 50%"说的就是这一发进管线时的量，
     * 也就是原版算完护甲、我们还没改之前的那个数）。它不搜半径——反弹的对象天然就是攻击者。</p>
     */
    private static @Nullable String planReflect(Bound entry, Action action, Bases bases,
                                               List<String> unsupported, List<ExtraHit> out) {
        double ratio = action.number("ratio", 0.0);
        String basis = action.body().has("basis") ? action.body().get("basis").getAsString() : "incoming";
        double amount = bases.of(basis) * ratio;
        if (!(amount > 0)) {
            unsupported.add(entry.cardId() + " 的 reflect：基数 " + basis + " × " + ratio + " = 0");
            return null;
        }
        out.add(new ExtraHit(entry.cardId(), amount, 0.0, Target.ATTACKER,
                "反弹 " + basisText(basis) + " ×" + ratio));
        return "反弹 " + rounded(amount) + " 点给攻击者（" + basisText(basis) + " × " + ratio + "）";
    }

    /**
     * "接下来 N 秒内的格挡一律按精准结算"：记一枚引擎自有的在途计数，时长就是那 N 秒。
     *
     * <p>用 {@link CounterStore} 而不是再造一个计时器，和冷却同一条机制；读它的是
     * {@link #forcedPrecise}。</p>
     */
    private static @Nullable String spendForceParry(String holder, Bound entry, Action action,
                                                   CounterStore counters, double nowSeconds) {
        double seconds = Math.max(0.0, action.number("seconds", 0.0));
        CounterStore.Key key = new CounterStore.Key(holder, forceParryResource(entry.cardId()));
        counters.gain(key, 1, new CounterStore.Rule(1, seconds, CounterStore.Expiry.REFRESH_ALL), nowSeconds);
        return "接下来 " + seconds + " 秒内的格挡一律按精准结算";
    }

    /** 某张卡的"强制精准"资源名（引擎命名空间内，内容由 `trigger.` 前缀被拦住进不来）。 */
    public static String forceParryResource(ResourceLocation cardId) {
        return COOLDOWN_PREFIX + cardId + "." + FORCE_PARRY;
    }

    /**
     * 有没有哪张卡正在强制精准。
     *
     * <p>答案是"精准"这件事会<em>照发 {@code parry_success}</em>——"一律按精准结算"要的就是
     * 让这 N 秒里所有"弹反成功后"的收益都吃得到；只改判定名字而不发事件，这条动作等于没做。
     * （《挂点覆盖表》把这条列为待确认，代码先按唯一自洽的读法实现。）</p>
     */
    public static boolean forcedPrecise(String holder, List<Bound> bound, CounterStore counters,
                                        double nowSeconds) {
        for (Bound entry : bound) {
            for (Action action : entry.clause().actions()) {
                if (!FORCE_PARRY.equals(action.type())) {
                    continue;
                }
                CounterStore.Key key = new CounterStore.Key(holder, forceParryResource(entry.cardId()));
                counters.expire(key, nowSeconds);
                if (counters.amount(key) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String planKnockback(Bound entry, Action action, List<KnockbackHit> out) {
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
     *
     * <p>事件累计（{@code trigger.count.*}）与轮转指针（{@code trigger.cursor.*}）<b>不算在途</b>：
     * 它们本来就永不过期，把它们算进来会让"还剩几次可用"这种读数变成几百。</p>
     */
    public static double inFlight(CounterStore counters, String holder, double nowSeconds) {
        Map<String, CounterStore.Held> byResource = counters.snapshot(nowSeconds)
                .getOrDefault(holder, Map.of());
        double total = 0.0;
        for (Map.Entry<String, CounterStore.Held> entry : byResource.entrySet()) {
            String resource = entry.getKey();
            if (resource.startsWith(COUNT_PREFIX) || resource.startsWith(CURSOR_PREFIX)) {
                continue;
            }
            if (resource.startsWith(COOLDOWN_PREFIX)) {
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
