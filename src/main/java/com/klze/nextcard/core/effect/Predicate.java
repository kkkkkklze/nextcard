package com.klze.nextcard.core.effect;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 判定条件（谓词）：只回答"现在成不成立"，不碰世界、不算数值。
 *
 * <p>注册表 + 组合子是卡表真正缺的那一层。《00》里的门槛几乎都不是单条件：
 * "满足任意一条即可"、"若…则…否则…"、"每三次一次"。叶子条件（{@link Condition}）再多也
 * 表达不了一句"或"，所以这里定下的是<em>树</em>的形状：叶子 {@code Condition} 与
 * {@link Combinators} 里的组合子实现同一个接口，{@code when} 数组里能混着写。</p>
 *
 * <p>{@link Verdict#reason()} 不是装饰：守方管线的第①步（免疫/否决）要求"把数打成 0 必须说得出
 * 是谁拦下的"（{@code DefencePipeline}），而那句理由只能从判据这里长出来——事后翻卡表是翻不出来的。</p>
 */
public interface Predicate {

    /** 成立与否 + 归因短句。 */
    Verdict test(Facts facts);

    /** 这个条件叫什么（留痕与报错用，不含具体数值）。 */
    String describe();

    /**
     * 这棵子树<em>点名</em>了哪些账本资源。执行器据此决定要读哪几个键——
     * 不整本抄账本是因为一张卡的树只有几个节点，而账本会随一局游戏一直长。
     *
     * <p>默认什么都不收：只有引用型叶子（{@code stacks} / {@code count}）与
     * {@code sequence} 才点名资源。</p>
     */
    default void collectRefs(Refs refs) {
    }

    /** 三本账的点名清单：叠层、事件累计、轮转指针。 */
    final class Refs {

        private final Set<String> stacks = new LinkedHashSet<>();
        private final Set<String> events = new LinkedHashSet<>();
        private final Set<String> cursors = new LinkedHashSet<>();

        public Refs stack(String id) {
            stacks.add(id);
            return this;
        }

        public Refs event(String name) {
            events.add(name);
            return this;
        }

        public Refs cursor(String id) {
            cursors.add(id);
            return this;
        }

        public Set<String> stacks() {
            return Set.copyOf(stacks);
        }

        public Set<String> events() {
            return Set.copyOf(events);
        }

        public Set<String> cursors() {
            return Set.copyOf(cursors);
        }

        public boolean isEmpty() {
            return stacks.isEmpty() && events.isEmpty() && cursors.isEmpty();
        }
    }

    /** 判定结论：不成立也要说清是哪一条差在哪。 */
    record Verdict(boolean holds, String reason) {

        public static Verdict yes(String reason) {
            return new Verdict(true, reason);
        }

        public static Verdict no(String reason) {
            return new Verdict(false, reason);
        }
    }
}
