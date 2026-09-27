package com.klze.nextcard.core.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nullable;

/**
 * 伤害结算管线（口径来自内容侧《02-名词表》4.1 与《00-总览大纲》若干条）。
 *
 * <p>公式写死成一条有序表，不是一堆散落的乘法：
 * {@code 基础 × 系数 × (1+全伤) × (1+本分类加成) × (1+专属buff) × (1+特殊伤害) × 暴击
 * × (1+方向增伤) × ∏(1-守方减伤) + 只加本伤害源 + 追加结算}。顺序＝这里声明的顺序，改动必须有裁定。</p>
 *
 * <p>几条内容侧口径被做成<b>会失败</b>的判据，而不是注释：</p>
 * <ul>
 *   <li><b>武器分类六选一</b>：靠"这一次攻击只有一个分类"来表达（{@code attackClass} 是单值），
 *       所以结构上就不可能同时吃到两个分类加成——不靠事后检查，也不靠"取第一个"。</li>
 *   <li><b>方向增伤封顶 120%</b>：只有卡面声明"超限"（{@code capExempted}）才允许突破。</li>
 *   <li><b>基础数值不得默认 0</b>：缺失即抛错；<b>吃不到的乘区按 0</b>：分类对不上就是 0，不报错。</li>
 *   <li><b>"只加本伤害源"与"追加结算"不进任何乘区</b>：它们在所有乘法之后加。</li>
 * </ul>
 *
 * <p>{@link Result#trace()} 逐步记「之前 → 之后」：内容作者问"这张卡为什么只打出这点伤害"，
 * 回答要能指到具体某一步，而不是让人反推公式。</p>
 */
public final class DamagePipeline {

    /** 方向增伤的封顶（120%）。 */
    public static final double DIRECTION_BONUS_CAP = 1.2;

    private DamagePipeline() {
    }

    /**
     * @param base             基础值（缺失即错误，绝不默认 0）
     * @param coefficient      系统系数
     * @param attackClass      这次攻击属于哪个分类（单值＝六选一）；null 表示不带分类
     * @param classBonuses     分类 → 加成，由效果层折好交进来
     * @param allDamage        全伤乘区
     * @param ownBuff          专属 buff 乘区
     * @param specialDamage    特殊伤害乘区
     * @param critMultiplier   暴击乘区（不暴击时给 1.0）
     * @param directionBonus   方向增伤（小数，0.2 = +20%）
     * @param capExempted      卡面是否声明"超限"
     * @param reductions       守方各来源减伤（小数），按 ∏(1-r) 叠乘
     * @param flatThisSource   只加本伤害源（乘区之外）
     * @param extraSettlements 追加结算（乘区之外）
     */
    public record Input(double base, double coefficient, @Nullable String attackClass,
                        Map<String, Double> classBonuses, double allDamage, double ownBuff,
                        double specialDamage, double critMultiplier, double directionBonus,
                        boolean capExempted, List<Double> reductions, double flatThisSource,
                        double extraSettlements) {

        public Input {
            classBonuses = new TreeMap<>(classBonuses);
            reductions = List.copyOf(reductions);
        }

        /** 六选一：只有这一格能用，其余分类的加成对本次攻击不产生影响。 */
        public double classBonus() {
            return attackClass == null ? 0.0 : classBonuses.getOrDefault(attackClass, 0.0);
        }
    }

    /** 结算结果 + 每步留痕。 */
    public record Result(double value, List<String> trace) {
    }

    public static Result resolve(Input input) {
        List<String> trace = new ArrayList<>();
        if (!(input.base() > 0)) {
            throw new IllegalArgumentException("基础数值不得默认 0，必须由调用点点名给出: " + input.base());
        }

        double value = input.base();
        trace.add("基础 " + formatted(value));

        value *= input.coefficient();
        trace.add("× 系数 " + input.coefficient() + " → " + formatted(value));

        value *= 1.0 + input.allDamage();
        trace.add("× (1+全伤 " + input.allDamage() + ") → " + formatted(value));

        double classBonus = input.classBonus();
        value *= 1.0 + classBonus;
        trace.add("× (1+" + (classBonus == 0.0 ? "0" : input.attackClass() + " " + classBonus)
                + ") 武器分类 → " + formatted(value));

        value *= 1.0 + input.ownBuff();
        trace.add("× (1+专属buff " + input.ownBuff() + ") → " + formatted(value));

        value *= 1.0 + input.specialDamage();
        trace.add("× (1+特殊伤害 " + input.specialDamage() + ") → " + formatted(value));

        value *= input.critMultiplier();
        trace.add("× 暴击 " + input.critMultiplier() + " → " + formatted(value));

        double direction = input.directionBonus();
        if (!input.capExempted() && direction > DIRECTION_BONUS_CAP) {
            trace.add("方向增伤 " + direction + " 被封顶到 " + DIRECTION_BONUS_CAP + "（卡面未声明超限）");
            direction = DIRECTION_BONUS_CAP;
        }
        value *= 1.0 + direction;
        trace.add("× (1+方向增伤 " + direction + ") → " + formatted(value));

        for (int i = 0; i < input.reductions().size(); i++) {
            double reduction = input.reductions().get(i);
            double before = value;
            value *= 1.0 - reduction;
            trace.add("× (1-减伤#" + i + " " + reduction + ") " + formatted(before) + " → " + formatted(value));
        }

        if (input.flatThisSource() != 0.0) {
            value += input.flatThisSource();
            trace.add("+ 只加本伤害源 " + input.flatThisSource() + " → " + formatted(value));
        }
        if (input.extraSettlements() != 0.0) {
            value += input.extraSettlements();
            trace.add("+ 追加结算 " + input.extraSettlements() + " → " + formatted(value));
        }
        return new Result(value, List.copyOf(trace));
    }

    private static String formatted(double value) {
        return String.format(Locale.ROOT, "%.4g", value);
    }
}
