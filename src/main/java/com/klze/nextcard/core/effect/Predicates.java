package com.klze.nextcard.core.effect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

import javax.annotation.Nullable;

/**
 * 判定条件注册表：卡面能写哪些条件、怎么拼、拼歪了在哪一步报出来，只有这一处知道。
 *
 * <p>三件事分开做，缺一条就会长出第二真相：</p>
 * <ol>
 *   <li><b>叶子</b>（{@link Condition}）：12 个原有键 + 卡表证实需要的 7 个（目标血量、距离、
 *       光照、发声、背后扇区、目标类别、致命）。键名不在表里 = 加载错误，绝不"未知即放行"。</li>
 *   <li><b>组合子</b>（{@link Combinators}）：全部/任意/非/若则/n 选 k/顺序轮转，同一个
 *       {@link Predicate} 接口，所以 {@code when} 数组里叶子和组合子能混着写。</li>
 *   <li><b>具名复合条件</b>（{@link #named}）：像「未察觉」这种在卡表里被当<em>一个</em>词用的
 *       组合，只在这里展开一次。它要是散进各张卡的 JSON 里，"未察觉"就会有几十种实现——
 *       那正是本模组最容易长 bug 的地方（层数口径的三个"寿命"就是这么错的）。</li>
 * </ol>
 *
 * <p>本类不读世界：判据一律作用在 {@link Facts} 快照上，因此每一条都能在 {@code logicTest} 里
 * 被证明（玩家开游戏点两下不算证据）。</p>
 */
public final class Predicates {

    /** 递归深度上限：超了就是卡面写歪了，加载期报错比栈溢出好查。 */
    public static final int MAX_DEPTH = 6;

    /** 背后扇区的半角：《00》"以背面中线为轴的两侧各 60°"（合计 120°）。 */
    public static final double BACK_SECTOR_HALF_ANGLE = 60.0;

    private static final Map<String, Supplier<Predicate>> NAMED = Map.of(
            "unseen", Predicates::unseen);

    private Predicates() {
    }

    /** 「未察觉」＝满足任意一条即可（《00》L1016 的三条，一条都不少、一条都不多）。 */
    public static Predicate unseen() {
        return new Combinators.Any(List.of(
                new Condition("back_sector", BACK_SECTOR_HALF_ANGLE, "", "", ""),
                new Combinators.Any(List.of(
                        new Condition("target_state", 0.0, "attracted_elsewhere", "", ""),
                        new Condition("target_state", 0.0, "controlled", "", ""),
                        new Condition("target_state", 0.0, "blind", "", ""))),
                new Condition("appeared_outside_view", 1.0, "", "", "")));
    }

    public static Set<String> leafNames() {
        return Condition.KEYS;
    }

    public static Set<String> namedNames() {
        return new TreeSet<>(NAMED.keySet());
    }

    /** 全部可写的条件名（叶子 + 具名复合 + 组合子），文档与加载期校验都读它。 */
    public static Set<String> vocabulary() {
        Set<String> all = new TreeSet<>(Condition.KEYS);
        all.addAll(NAMED.keySet());
        all.addAll(Combinators.KEYS);
        return all;
    }

    public static @Nullable Predicate named(String name) {
        Supplier<Predicate> factory = NAMED.get(name);
        return factory == null ? null : factory.get();
    }

    /**
     * 解析一个条件节点：具名复合条件 → 组合子 → 叶子，三条路按顺序试。
     *
     * @param depth 当前深度（{@link #MAX_DEPTH} 之外报加载错误）
     */
    public static @Nullable Predicate parse(JsonObject json, List<String> errors, int depth) {
        if (depth > MAX_DEPTH) {
            errors.add("condition nested deeper than " + MAX_DEPTH + " levels — split it into several cards");
            return null;
        }
        List<String> keys = new ArrayList<>(json.keySet());
        if (keys.size() == 1 && NAMED.containsKey(keys.get(0))) {
            String name = keys.get(0);
            JsonElement value = json.get(name);
            // 具名复合条件写成布尔即可（{"unseen": true}）；写 false 表示"要求它不成立"。
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                errors.add("named condition " + name + " needs true/false");
                return null;
            }
            Predicate named = NAMED.get(name).get();
            return value.getAsBoolean() ? named : new Combinators.Not(named);
        }
        if (keys.stream().anyMatch(Combinators::isCombinator)) {
            return Combinators.parse(json, errors, depth);
        }
        return Condition.parse(json, errors);
    }

    /** 解析一个条件节点数组；不是数组时返回 null 并报错（不静默当空表）。 */
    public static @Nullable List<Predicate> parseArray(JsonElement element, String where,
                                                       List<String> errors, int depth) {
        if (element == null || element.isJsonNull()) {
            errors.add(where + " needs an array of conditions");
            return null;
        }
        if (!element.isJsonArray()) {
            errors.add(where + " must be an array, got " + element);
            return null;
        }
        List<Predicate> out = new ArrayList<>();
        JsonArray array = element.getAsJsonArray();
        for (JsonElement entry : array) {
            if (!entry.isJsonObject()) {
                errors.add(where + " entries must be objects, got " + entry);
                continue;
            }
            Predicate parsed = parse(entry.getAsJsonObject(), errors, depth + 1);
            if (parsed != null) {
                out.add(parsed);
            }
        }
        return out;
    }

    /**
     * 整棵树的引用校验：叶子引用的叠层必须被某张卡声明过（跨卡校验从 {@code EffectClauses} 进来）。
     *
     * <p>走树而不是只看第一层：条件嵌在 {@code any_of} 里的那条"「杀意」≥3 层"同样会引用不存在
     * 的叠层，只查顶层等于给它留了个静默通道。</p>
     */
    public static void validateTree(Predicate node, Set<String> declaredStacks, String where,
                                    List<String> errors) {
        if (node instanceof Condition leaf) {
            String stackId = leaf.referencedStack();
            if (!stackId.isEmpty() && !declaredStacks.contains(stackId)) {
                errors.add(where + ": condition references undeclared stack " + stackId);
            }
            return;
        }
        for (Predicate child : children(node)) {
            validateTree(child, declaredStacks, where, errors);
        }
    }

    private static List<Predicate> children(Predicate node) {
        if (node instanceof Combinators.All all) {
            return all.children();
        }
        if (node instanceof Combinators.Any any) {
            return any.children();
        }
        if (node instanceof Combinators.KOfN kOfN) {
            return kOfN.children();
        }
        if (node instanceof Combinators.Sequence sequence) {
            return sequence.children();
        }
        if (node instanceof Combinators.Not not) {
            return List.of(not.child());
        }
        if (node instanceof Combinators.If branch) {
            List<Predicate> children = new ArrayList<>(List.of(branch.guard(), branch.thenCon()));
            if (branch.otherwise() != null) {
                children.add(branch.otherwise());
            }
            return children;
        }
        return List.of();
    }

    /** {@code when} 数组的语义：全部成立才算成立（要"或"就在数组里写一条 {@code any_of}）。 */
    public static Predicate.Verdict allHold(List<Predicate> when, Facts facts) {
        if (when.isEmpty()) {
            return Predicate.Verdict.yes("无条件");
        }
        return new Combinators.All(when).test(facts);
    }
}
