package com.klze.nextcard.core.effect;

/**
 * 护甲穿透（{@code channel.armor_pierce}）的<em>纯算式</em>：卡面写"无视 30% 护甲"，
 * 这里算出"那一发之前该从目标身上借走几点护甲"。
 *
 * <p>为什么是"借护甲"而不是"自己算一遍护甲"：引擎的接管点
 * （{@code LivingDamageEvent}）在原版算完护甲<em>之后</em>，自己再算一次就是让它生效两遍
 * （{@code DefencePipeline} 的类注释写着这条）。而 {@code LivingHurtEvent} 恰好发在
 * {@code getDamageAfterArmorAbsorb} 的前一行，所以穿透可以是"把那部分护甲在算之前借走、
 * 算完立刻还"——曲线仍然只由原版算一次。</p>
 *
 * <p>只借 {@code ARMOR}，不动 {@code ARMOR_TOUGHNESS}：卡面说的是"无视护甲"，而韧性在原版曲线里
 * 管的是高护甲段的衰减形状，跟着一起按比例削会得到一个正本里没有的东西。</p>
 */
public final class ArmourPiercing {

    private ArmourPiercing() {
    }

    /** 比例夹在 0~1：1.0 = 完全无视，超过 1 不等于"把护甲变成负的"。 */
    public static double clamped(double pierce) {
        if (!(pierce > 0.0)) {
            return 0.0;
        }
        return Math.min(1.0, pierce);
    }

    /**
     * 该借走几点护甲。目标本来没有护甲（或属性是负的）时借不走任何东西——
     * 穿透不是"造成额外伤害"的另一个通道。
     */
    public static double armorToStrip(double armor, double pierce) {
        if (!(armor > 0.0)) {
            return 0.0;
        }
        double ratio = clamped(pierce);
        return ratio <= 0.0 ? 0.0 : Math.min(armor, armor * ratio);
    }
}
