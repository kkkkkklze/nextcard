package com.klze.nextcard.core.effect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * 组合子：把叶子条件拼成树（判定条件那一层的"定制效果不定制卡"）。
 *
 * <p>为什么必须有它们——首板卡表里这些句子成堆出现：</p>
 * <ul>
 *   <li>{@code 任意一条}：《00》L1016 "前置：目标必须「未察觉」你，满足任意一条即可"；</li>
 *   <li>{@code 若…否则…}：处决"普通敌人直接斩杀；精英与 boss 永远只吃重创"；</li>
 *   <li>{@code 每三次一次}：卡表里"第三次攻击额外…"这类节奏（轮转指针归调用方持久化）；</li>
 *   <li>{@code n 条里成立 k 条}：多条件奖励池的常见写法。</li>
 * </ul>
 *
 * <p><b>递归有深度上限</b>（{@link Predicates#MAX_DEPTH}）：卡面写成自指嵌套时，加载期报错比
 * 栈溢出好查。这条限制同时也是给内容侧的信号——超过六层的条件树应该拆成多张卡或具名条件，
 * 而不是继续往里塞。</p>
 */
public final class Combinators {

    /** 组合子键名（与叶子条件键互斥，{@link Predicates#parse} 按它分派）。 */
    public static final List<String> KEYS = List.of("all_of", "any_of", "not", "if", "k_of_n", "sequence");

    private Combinators() {
    }

    public static boolean isCombinator(String key) {
        return KEYS.contains(key);
    }

    /** 全部成立（也是 {@code when} 数组的默认拼法，所以 {@code all_of} 通常是冗余的）。 */
    public record All(List<Predicate> children) implements Predicate {

        public All {
            children = List.copyOf(children);
        }

        @Override
        public Verdict test(Facts facts) {
            List<String> failed = new ArrayList<>();
            List<String> held = new ArrayList<>();
            for (Predicate child : children) {
                Verdict verdict = child.test(facts);
                if (verdict.holds()) {
                    held.add(child.describe() + "（" + verdict.reason() + "）");
                } else {
                    failed.add(child.describe() + "（" + verdict.reason() + "）");
                }
            }
            return failed.isEmpty() ? Verdict.yes("全部成立：" + held)
                    : Verdict.no("未成立：" + String.join("、", failed));
        }

        @Override
        public String describe() {
            return "同时满足 " + children.size() + " 条";
        }

        @Override
        public void collectRefs(Refs refs) {
            collectAll(children, refs);
        }
    }

    /** 任意一条成立。短路求值：第一条款成立就停，但归因会指出是哪一条放行的。 */
    public record Any(List<Predicate> children) implements Predicate {

        public Any {
            children = List.copyOf(children);
        }

        @Override
        public Verdict test(Facts facts) {
            List<String> missed = new ArrayList<>();
            for (Predicate child : children) {
                Verdict verdict = child.test(facts);
                if (verdict.holds()) {
                    return Verdict.yes(child.describe() + "（" + verdict.reason() + "）");
                }
                missed.add(child.describe() + "（" + verdict.reason() + "）");
            }
            return Verdict.no("一条都不成立：" + String.join("、", missed));
        }

        @Override
        public String describe() {
            return "任意一条（" + children.size() + " 选 1）";
        }

        @Override
        public void collectRefs(Refs refs) {
            collectAll(children, refs);
        }
    }

    /** 取反。<b>不</b>反转归因：理由写的仍是里面那条为什么成立/不成立。 */
    public record Not(Predicate child) implements Predicate {

        @Override
        public Verdict test(Facts facts) {
            Verdict verdict = child.test(facts);
            return new Verdict(!verdict.holds(), "非「" + child.describe() + "」：" + verdict.reason());
        }

        @Override
        public String describe() {
            return "非「" + child.describe() + "」";
        }

        @Override
        public void collectRefs(Refs refs) {
            child.collectRefs(refs);
        }
    }

    /**
     * 若…则…（否则…）。{@code otherwise} 可以为空——没有 else 时不成立就是整体不成立，
     * 不猜一个默认值。
     */
    public record If(Predicate guard, Predicate thenCon, @Nullable Predicate otherwise) implements Predicate {

        @Override
        public Verdict test(Facts facts) {
            Verdict gate = guard.test(facts);
            if (gate.holds()) {
                Verdict branch = thenCon.test(facts);
                return new Verdict(branch.holds(), "若「" + guard.describe() + "」成立→" + branch.reason());
            }
            if (otherwise == null) {
                return Verdict.no("若「" + guard.describe() + "」不成立：" + gate.reason());
            }
            Verdict branch = otherwise.test(facts);
            return new Verdict(branch.holds(),
                    "否则走「" + otherwise.describe() + "」：" + branch.reason());
        }

        @Override
        public String describe() {
            return otherwise == null ? "若「" + guard.describe() + "」则「" + thenCon.describe() + "」"
                    : "若「" + guard.describe() + "」则「" + thenCon.describe() + "」，否则「"
                            + otherwise.describe() + "」";
        }

        @Override
        public void collectRefs(Refs refs) {
            guard.collectRefs(refs);
            thenCon.collectRefs(refs);
            if (otherwise != null) {
                otherwise.collectRefs(refs);
            }
        }
    }

    /** n 条里成立 k 条。 */
    public record KOfN(int k, List<Predicate> children) implements Predicate {

        public KOfN {
            children = List.copyOf(children);
            if (k < 1 || k > children.size()) {
                throw new IllegalArgumentException("k_of_n 需要 1 ≤ k ≤ 条数，实际 k=" + k
                        + "，条数=" + children.size());
            }
        }

        @Override
        public Verdict test(Facts facts) {
            int held = 0;
            List<String> reasons = new ArrayList<>();
            for (Predicate child : children) {
                Verdict verdict = child.test(facts);
                if (verdict.holds()) {
                    held++;
                }
                reasons.add(child.describe() + (verdict.holds() ? " ✓" : " ✗"));
            }
            return new Verdict(held >= k, held + "/" + children.size() + " 成立，需 " + k + "：" + reasons);
        }

        @Override
        public String describe() {
            return children.size() + " 条里成立 " + k + " 条";
        }

        @Override
        public void collectRefs(Refs refs) {
            collectAll(children, refs);
        }
    }

    /**
     * 顺序轮转：这一次只看第 {@code cursor} 条，看完由调用方把指针推进。
     *
     * <p>指针<em>不在这里存</em>：它是"这个玩家在这张卡上走到第几步"，属于持久对象
     * （{@link CounterStore} 的一条资源，键是 {@code trigger.cursor.<id>}），由执行器取出来放进
     * {@link Facts#count(String)}。所以轮转本身仍是纯函数，可无头断言。
     * 循环（"每 N 次一轮，转完再来"）是同一条判据取模后的样子，不另开一种组合子。</p>
     *
     * <p><b>推进时机只有一种读法</b>：整条 {@code when} 门槛<em>成立</em>之后才 +1，不是"看过一次就推进"。
     * "每三段刀路各不同"要的是走完这一步才进下一步；走不完就一直停在这一步（这也是 {@code sequence}
     * 与 {@code count} 共用一本账、却不共用一个键族的原因）。卡表里目前没有卡用轮转，
     * 所以这条先按唯一自洽的读法定下；若内容侧其实想要"每次派动都推进"，要说一句再改。</p>
     */
    public record Sequence(String id, List<Predicate> children) implements Predicate {

        public Sequence {
            children = List.copyOf(children);
            if (children.isEmpty()) {
                throw new IllegalArgumentException("sequence 至少要有一条");
            }
        }

        @Override
        public Verdict test(Facts facts) {
            int cursor = facts.count(id);
            int index = Math.floorMod(cursor, children.size());
            Predicate current = children.get(index);
            Verdict verdict = current.test(facts);
            return new Verdict(verdict.holds(), "第 " + (index + 1) + "/" + children.size()
                    + " 步「" + current.describe() + "」：" + verdict.reason());
        }

        @Override
        public String describe() {
            return "顺序轮转 " + children.size() + " 步（指针 " + id + "）";
        }

        @Override
        public void collectRefs(Refs refs) {
            refs.cursor(id);
            collectAll(children, refs);
        }
    }

    /** 组合子的子节点逐个收一遍（叶子自己点名，组合子只往下传）。 */
    static void collectAll(List<Predicate> children, Predicate.Refs refs) {
        for (Predicate child : children) {
            child.collectRefs(refs);
        }
    }

    /**
     * 解析一个组合子对象（{@link Predicates#parse} 已经确认过键名在 {@link #KEYS} 里）。
     *
     * @param depth 当前嵌套深度，超过 {@link Predicates#MAX_DEPTH} 报加载错误
     */
    public static Predicate parse(JsonObject json, List<String> errors, int depth) {
        List<String> keys = new ArrayList<>(json.keySet());
        List<String> combinatorKeys = keys.stream().filter(Combinators::isCombinator).toList();
        if (combinatorKeys.size() != 1) {
            errors.add("a condition node must be exactly one combinator of " + KEYS + ", got " + keys);
            return null;
        }
        String kind = combinatorKeys.get(0);
        Set<String> companions = companionKeys(kind);
        List<String> unexpected = keys.stream()
                .filter(key -> !key.equals(kind) && !companions.contains(key))
                .toList();
        if (!unexpected.isEmpty()) {
            errors.add("combinator " + kind + " has unexpected fields " + unexpected
                    + "（这一类只接受 " + companions + "）");
            return null;
        }
        JsonElement value = json.get(kind);
        return switch (kind) {
            case "all_of", "any_of" -> {
                List<Predicate> children = Predicates.parseArray(value, "combinator " + kind, errors, depth);
                if (children == null) {
                    yield null;
                }
                if (children.isEmpty()) {
                    errors.add("combinator " + kind + " needs at least one child");
                    yield null;
                }
                yield kind.equals("all_of") ? new All(children) : new Any(children);
            }
            case "not" -> {
                if (!value.isJsonObject()) {
                    errors.add("combinator not needs a single condition object");
                    yield null;
                }
                Predicate child = Predicates.parse(value.getAsJsonObject(), errors, depth + 1);
                yield child == null ? null : new Not(child);
            }
            case "if" -> parseIf(json, value, errors, depth);
            case "k_of_n" -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                    errors.add("combinator k_of_n needs a number, with an \"of\" array");
                    yield null;
                }
                List<Predicate> children = Predicates.parseArray(json.get("of"), "k_of_n.of", errors, depth);
                if (children == null) {
                    yield null;
                }
                int k = value.getAsInt();
                if (k < 1 || k > children.size()) {
                    // 写成加载错误而不是让它抛出去：卡面报"这张卡没生效"没人会去翻堆栈
                    errors.add("k_of_n needs 1 ≤ k ≤ " + children.size() + ", got " + k);
                    yield null;
                }
                yield new KOfN(k, children);
            }
            case "sequence" -> {
                List<Predicate> children = Predicates.parseArray(value, "combinator sequence", errors, depth);
                if (children == null) {
                    yield null;
                }
                String cursorId = json.has("cursor") ? json.get("cursor").getAsString() : "";
                if (cursorId.isEmpty()) {
                    errors.add("combinator sequence needs a \"cursor\" naming the persistent pointer");
                    yield null;
                }
                if (children.isEmpty()) {
                    errors.add("combinator sequence needs at least one step");
                    yield null;
                }
                yield new Sequence(cursorId, children);
            }
            default -> {
                errors.add("unknown combinator: " + kind);
                yield null;
            }
        };
    }

    private static Predicate parseIf(JsonObject json, JsonElement guard, List<String> errors, int depth) {
        if (!guard.isJsonObject()) {
            errors.add("combinator if needs an object guard, plus \"then\" and optional \"else\"");
            return null;
        }
        Predicate predicate = Predicates.parse(guard.getAsJsonObject(), errors, depth + 1);
        Predicate thenCon = parseBranch(json, "then", errors, depth + 1);
        if (predicate == null || thenCon == null) {
            return null;
        }
        Predicate otherwise = json.has("else") ? parseBranch(json, "else", errors, depth + 1) : null;
        return new If(predicate, thenCon, otherwise);
    }

    private static @Nullable Predicate parseBranch(JsonObject json, String field, List<String> errors, int depth) {
        if (!json.has(field) || !json.get(field).isJsonObject()) {
            errors.add("combinator if needs an object in \"" + field + "\"");
            return null;
        }
        return Predicates.parse(json.getAsJsonObject(field), errors, depth);
    }

    /** 每种组合子允许的伴生字段（写在同一个对象里，不另开一层）。 */
    private static Set<String> companionKeys(String kind) {
        return switch (kind) {
            case "if" -> Set.of("then", "else");
            case "k_of_n" -> Set.of("of");
            case "sequence" -> Set.of("cursor");
            default -> Set.of();
        };
    }
}
