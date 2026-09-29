package com.klze.nextcard.core.effect;

/**
 * 举盾挡下多少（<b>纯算式</b>，不碰 MC：这样"改这个数到底改变了什么"能在无头环境里被证明）。
 *
 * <p>1.20.1 原版的行为是"挡下的那一发整个取消"——{@code ShieldBlockEvent} 里
 * {@code blockedDamage} 默认等于进来的全部伤害。本模组把"挡掉多少"变成一个可调的数
 * （{@code data/nextcard/manifest.json} 的 {@code combat.block_reduction}），
 * 出厂值 <b>1.0 = 与原版完全一致</b>：这个数是给内容侧在游戏里改 JSON 试手感用的，
 * 引擎不替他们决定手感（klze 2026-09-30）。</p>
 */
public final class ShieldBlock {

    private ShieldBlock() {
    }

    /** 比例夹在 0~1：负数等于"挡了个负的"，大于 1 等于"挡出额外的伤害"，都不是能接受的行为。 */
    public static double clamped(double reduction) {
        if (!(reduction > 0.0)) {
            return 0.0;
        }
        return Math.min(1.0, reduction);
    }

    /** 这一发里被<em>取消</em>掉多少（交给 {@code setBlockedDamage} 的那个值）。 */
    public static double blockedOf(double incoming, double reduction) {
        return incoming <= 0.0 ? 0.0 : incoming * clamped(reduction);
    }

    /** 剩下要照常走护甲与减免的那部分。 */
    public static double remainingOf(double incoming, double reduction) {
        return incoming - blockedOf(incoming, reduction);
    }
}
