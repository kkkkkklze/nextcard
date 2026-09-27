package com.klze.nextcard.core.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 判定窗口的合成（盾反）。池说明给定的算法，这里是它唯一的实现处：
 *
 * <pre>
 * 窗口 = 基础窗口 ×（1 + 所有延长项之和）× 所有乘区 ÷ 所有难度项
 * </pre>
 *
 * <p>"延长项走加法、难度项走除法、乘区走乘法"不是约定，而是槽位的合成方式：
 * {@code window.length} 声明 {@link Mechanics.Combine#ADD}、{@code window.scale} 声明
 * {@code MULTIPLY}、{@code window.difficulty} 声明 {@code DIVIDE}。一张卡只能改槽位，
 * 改不了合成方式——所以"这张卡的延长是乘算"这种卡面写法在结构上不存在。</p>
 */
public final class WindowMath {

    /** 最窄窗口下限：难度项无限叠加时不至于变成 0（0 窗口等于没有判定）。 */
    private static final double MIN_WINDOW = 0.01;

    private WindowMath() {
    }

    /** 由四段折叠后的数值算出窗口长度（秒）。纯函数。 */
    public static double window(double base, double additive, double scale, double difficulty) {
        double length = base * (1.0 + additive) * scale / difficulty;
        return Math.max(MIN_WINDOW, length);
    }

    /** 把一组修饰按槽位折叠后算窗口；{@code layers} 提供每层来源的层数。 */
    public static double windowFrom(double base, List<ModifierClause> modifiers, ToIntFunction<String> layers) {
        return window(base,
                sum(modifiers, "window.length", layers),
                product(modifiers, "window.scale", layers),
                product(modifiers, "window.difficulty", layers));
    }

    /**
     * 从<b>已经折好的快照</b>算窗口——难度那一段在这里是<em>乘</em>，不是除。
     *
     * <p>为什么和 {@link #window} 反着来：{@code window.difficulty} 槽位自己声明的是
     * {@link Mechanics.Combine#DIVIDE}，折叠时已经把"÷3"落成 1/3 了；这里再除一次就是
     * 把同一个难度项生效两遍（反过来算会得到 2.4 而不是 0.6，实测过）。
     * 两种读法都留在这个类里，是为了让"哪一步在折、哪一步在乘"只有一处答案。</p>
     */
    public static double windowFromProfile(double base, MechanicProfile profile) {
        double length = base * (1.0 + profile.number("window.length", 0.0));
        double withScale = length * profile.number("window.scale", 1.0);
        double withDifficulty = withScale * profile.number("window.difficulty", 1.0);
        return Math.max(MIN_WINDOW, withDifficulty);
    }

    /** 某个槽位上所有无条件（或条件已成立）改写的和。 */
    public static double sum(List<ModifierClause> modifiers, String target, ToIntFunction<String> layers) {
        double total = 0.0;
        for (ModifierClause modifier : modifiers) {
            if (modifier.target().equals(target)) {
                total += modifier.valueFor(layers);
            }
        }
        return total;
    }

    /** 某个槽位上所有改写的乘积（乘区 / 难度）。 */
    public static double product(List<ModifierClause> modifiers, String target, ToIntFunction<String> layers) {
        double total = 1.0;
        for (ModifierClause modifier : modifiers) {
            if (modifier.target().equals(target)) {
                total *= modifier.valueFor(layers);
            }
        }
        return total;
    }

    /** 某个槽位上带条件的改写（条件判定在运行期，这里只把它们挑出来）。 */
    public static List<ModifierClause> conditional(List<ModifierClause> modifiers, String target) {
        List<ModifierClause> found = new ArrayList<>();
        for (ModifierClause modifier : modifiers) {
            if (modifier.target().equals(target) && !modifier.unconditional()) {
                found.add(modifier);
            }
        }
        return found;
    }
}
