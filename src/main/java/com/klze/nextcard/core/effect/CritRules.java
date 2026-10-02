package com.klze.nextcard.core.effect;

import javax.annotation.Nullable;

/**
 * 暴击的唯一算法（面板概率、倍率、以及"暴击率满了之后溢出再暴击"那条规则）。
 *
 * <p>口径由 klze 定于 2026-09-29（见 {@code docs/口径对齐__暴击与方向增伤-2026-09-29.md}）：
 * <b>初始暴击率 5%、初始暴击伤害 130%、不要原版跳跃暴击、暴伤无上限；暴击率超过 100% 的部分
 * 让这一发再暴击一次</b>。四条都只在这里写一遍，接管点与管线都读这个类。</p>
 *
 * <p>"溢出再暴击"的读法要说清，因为它是唯一会终止的那种：面板暴击率 {@code c} 拆成
 * {@code floor(c)} 次<em>必定</em>暴击，加上小数部分那一次<em>概率</em>暴击。
 * 于是 100% 是一句"必定一次"，135% 是"必定一次 + 35% 再来一次"，240% 是"必定两次 + 40% 第三次"。
 * 另一种读法（每次暴击后拿溢出概率再 roll、可无限连锁）在溢出 ≥100% 时永不终止，
 * 而且期望次数会跳出卡面能算的范围，所以不采用。</p>
 *
 * <p>倍率是<em>每次暴击各乘一遍</em>（{@code 倍率 ^ 次数}），因为卡面的"+20% 暴击伤害"是
 * 面板增量、要进同一个乘区；倍率下限夹在 0（负数倍率会把伤害变成符号游戏）。</p>
 */
public final class CritRules {

    /** 初始暴击率（面板基线，卡面的 {@code channel.crit_chance} 是往上加的增量）。 */
    public static final double BASE_CHANCE = 0.05;

    /** 初始暴击伤害倍率：130% ⇒ 一次暴击 ×1.3。 */
    public static final double BASE_MULTIPLIER = 1.3;

    /** "满了"的那条线——超过它不是夹住，而是每满 100% 多一次必定暴击。 */
    public static final double CHANCE_CAP = 1.0;

    /**
     * "这一发<em>不掷</em>暴击判定"的那个值。
     *
     * <p>为什么不能拿 {@code roll = 1.0} 当"不暴击"：面板 ≥ 100% 时{@code floor} 那一部分是
     * <em>必定</em>暴击，不需要 roll——1.0 只能表达"概率那一次没中"，表达不了"根本不判定"。
     * 纯推演与既有断言走的就是这条不判定的路（它们要的是"暴击这一格不参与"）。</p>
     */
    public static final double NO_ROLL = -1.0;

    /**
     * "只拿<em>必定</em>那一部分、概率那一次不赌"的那个 roll 值。
     *
     * <p>用途只有一个：调用方走的是"不判定暴击"那条入口（{@link #NO_ROLL}），但这一发身上<em>有武装</em>
     * ——卡面写的是"必暴"，那就至少该拿到 {@code floor(面板)} 次，而不是整格跳过。取 {@code 1.0}
     * 是因为 {@code critCount} 判的是 {@code roll < 小数部分}，任何 {@code >= 1} 的值都恰好只让
     * 必定那部分成立。</p>
     */
    public static final double GUARANTEED_ONLY = 1.0;

    private CritRules() {
    }

    /**
     * 一次性<em>武装</em>："下一次攻击"临时加的那两格（klze 2026-09-30 裁定："下一次攻击可以视为
     * 有条件的临时 Buff，给个攻击就消失的 100% 暴击率效果就行"）。
     *
     * <p>它<em>进面板</em>而不是替换面板：一条 "+100% 暴击率" 与面板 5% 相加就是 105%，
     * 于是按本类那条溢出规则得到"必定一次 + 5% 再来一次"。这是裁定里两条规则本来该有的交集，
     * 不是额外发明的机制。</p>
     *
     * @param chance 加进暴击率的量（{@code 1.0} = 用户口中那个"100% 暴击率效果"）
     * @param damage 加进<em>单次</em>暴击倍率的量（0.3 = 暴伤从 130% 变 160%）
     */
    public record Armed(double chance, double damage) {
        public static final Armed NONE = new Armed(0.0, 0.0);
    }

    /** 面板暴击率（基线 + 卡面增量；负增量最多把面板压到 0）。 */
    public static double chance(@Nullable MechanicProfile profile) {
        return chance(profile, Armed.NONE);
    }

    /** 面板暴击率 + 一次性武装那一条（可以 &gt; 1，溢出按本类那条规则变成多次暴击）。 */
    public static double chance(@Nullable MechanicProfile profile, Armed armed) {
        return Math.max(0.0, BASE_CHANCE + (profile == null ? 0.0 : profile.channel("crit_chance"))
                + armed.chance());
    }

    /** 单次暴击的倍率（基线 1.3 + 卡面 {@code crit_damage} 增量，<b>无上限</b>，下限 0）。 */
    public static double multiplier(@Nullable MechanicProfile profile) {
        return multiplier(profile, Armed.NONE);
    }

    /** 同上，加上一次性武装那一条暴伤。 */
    public static double multiplier(@Nullable MechanicProfile profile, Armed armed) {
        double bonus = (profile == null ? 0.0 : profile.channel("crit_damage")) + armed.damage();
        return Math.max(0.0, BASE_MULTIPLIER + bonus);
    }

    /**
     * 这一发暴击几次。
     *
     * @param chance 面板暴击率（可以 &gt; 1）
     * @param roll   {@code [0,1)} 的一次随机数——由调用点交进来，<b>不在这里造随机</b>，
     *               否则无头断言就没法稳定（原版 {@code level.random} 由接管点传进来）
     */
    public static int critCount(double chance, double roll) {
        if (roll < 0.0) {
            return 0;   // 不判定这一格（见 {@link #NO_ROLL}）
        }
        if (!(chance > 0.0)) {
            return 0;
        }
        int guaranteed = (int) Math.floor(chance / CHANCE_CAP);
        double rest = chance - guaranteed * CHANCE_CAP;
        return roll < rest ? guaranteed + 1 : guaranteed;
    }

    /** 暴击之后的总乘区系数（{@code 倍率 ^ 次数}；不暴击给 1.0，也就是"这一格不适用"）。 */
    public static double totalMultiplier(double perCrit, int crits) {
        return crits <= 0 ? 1.0 : Math.pow(perCrit, crits);
    }

    /** 暴击之后的总乘区系数（读面板）。 */
    public static double totalMultiplier(@Nullable MechanicProfile profile, double roll) {
        return totalMultiplier(multiplier(profile), critCount(chance(profile), roll));
    }
}
