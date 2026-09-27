package com.klze.nextcard.core.effect;

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
