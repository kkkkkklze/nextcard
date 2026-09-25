package com.klze.nextcard.core.effect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 蓄力表（蓄力）：把"按住多久 → 放出哪一招"从卡面文字变成可求解的数据。
 *
 * <p>三条规则全部来自卡表，且都不需要特判：</p>
 * <ol>
 *   <li><b>蓄力条上限取最高、加成加算</b>——招式卡各自给出一段上限（
 *       {@code charge.bar_max} 声明 {@link Mechanics.Combine#MAX}），把上限继续往上推的卡给加成
 *       （{@code charge.bar_bonus} 声明 {@code ADD}）。</li>
 *   <li><b>到点覆盖</b>——每个蓄力点一个触发子句（{@code at} 秒），
 *       时刻 t 生效的是"at ≤ t 里最高的那一档"。</li>
 *   <li><b>可继承</b>——某档声明 {@code inherits} 时，连它下面那档一起生效
 *       （卡表原话：3 秒档"保留 2 秒那一档的加成"）。</li>
 * </ol>
 *
 * <p>超出上限的蓄力不增加伤害，转成「过量蓄势」层数（{@link #overcharge}）。</p>
 */
public record ChargeTable(boolean granted, double barMax, List<Tier> tiers) {

    /** 未取得蓄力能力时的上限（卡表：没有招式卡时"最多只能蓄到 0.5 秒，意思一下"）。 */
    public static final double DEFAULT_BAR = 0.5;

    /** 过量蓄势的段长。卡表写"每多蓄一段转 1 层"但没给段长——暂定 1 秒，待拍板。 */
    public static final double OVERCHARGE_SEGMENT = 1.0;

    /** 秒级比较容差（蓄力点是小的十进制数，避免 0.5 与 0.4999999 这种比较误差）。 */
    private static final double EPSILON = 1e-9;

    /** 一个蓄力点上的招式。{@code inherits} = 该档同时保留下一档的加成。 */
    public record Tier(double atSeconds, boolean inherits, TriggerClause clause) {
    }

    public ChargeTable {
        tiers = List.copyOf(tiers);
    }

    public static ChargeTable of(List<MechanicClause> mechanics, List<TriggerClause> triggers,
                                 List<ModifierClause> modifiers, ToIntFunction<String> layers) {
        boolean granted = false;
        double declared = DEFAULT_BAR;
        for (MechanicClause mechanic : mechanics) {
            if (mechanic.id().equals("charge")) {
                granted = true;
                declared = Math.max(declared, mechanic.param("bar_max", DEFAULT_BAR));
            }
        }
        if (!granted) {
            return new ChargeTable(false, DEFAULT_BAR, List.of());
        }
        // 上限类：声明值先取最高，再让改写按"取最宽"继续抬（开山是同一条链上的 +2 秒加成）。
        for (ModifierClause modifier : modifiers) {
            if (modifier.target().equals("charge.bar_max")) {
                declared = Math.max(declared, modifier.valueFor(layers));
            }
        }
        double barMax = declared + WindowMath.sum(modifiers, "charge.bar_bonus", layers);
        List<Tier> tiers = new ArrayList<>();
        for (TriggerClause trigger : triggers) {
            if (trigger.on().equals("charge_release") && trigger.atSeconds() >= 0) {
                tiers.add(new Tier(trigger.atSeconds(), trigger.inherits(), trigger));
            }
        }
        tiers.sort(Comparator.comparingDouble(Tier::atSeconds));
        return new ChargeTable(true, barMax, tiers);
    }

    /**
     * 时刻 {@code seconds} 处生效的全部招式：到点覆盖（取 at ≤ seconds 的最高档，
     * 同一档上的多张卡一起生效）+ 可继承链（声明 inherits 的档会把下面一档一并带出）。
     */
    public List<Tier> at(double seconds) {
        double highest = -1.0;
        for (Tier tier : tiers) {
            if (tier.atSeconds() <= seconds + EPSILON) {
                highest = Math.max(highest, tier.atSeconds());
            }
        }
        if (highest < 0.0) {
            return List.of();
        }
        List<Tier> effective = new ArrayList<>();
        double current = highest;
        while (current >= 0.0) {
            boolean inherits = false;
            for (Tier tier : tiers) {
                if (Math.abs(tier.atSeconds() - current) <= EPSILON) {
                    effective.add(tier);
                    inherits |= tier.inherits();
                }
            }
            if (!inherits) {
                break;
            }
            double lower = -1.0;
            for (Tier tier : tiers) {
                if (tier.atSeconds() < current - EPSILON) {
                    lower = Math.max(lower, tier.atSeconds());
                }
            }
            current = lower;
        }
        return List.copyOf(effective);
    }

    /** 超过上限的部分折算的「过量蓄势」层数。 */
    public double overcharge(double seconds) {
        if (seconds <= barMax) {
            return 0.0;
        }
        return Math.floor((seconds - barMax) / OVERCHARGE_SEGMENT);
    }
}
