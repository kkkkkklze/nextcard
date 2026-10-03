package com.klze.nextcard.core.effect;

import javax.annotation.Nullable;

/**
 * 吸血（{@code channel.lifesteal}）的唯一算式：<b>以<em>真的落到目标身上</em>的那个数回复攻击者</b>。
 *
 * <p>基数为什么取"落地后的数"而不是"结算前的总量"：这与 {@code damage_dealt} 事件同一条律——
 * 引擎里"我造成了伤害"这件事只看最终值（被对面免疫成 0 的那发不算伤害，也就不该算吸血）。
 * 另一作者的实现写的是 {@code total × lifesteal}（结算前的量），那是<em>另一种自洽口径</em>；
 * 换成它只需要改这一个函数，所以先把口径写在这里，而不是散在调用点里。</p>
 *
 * <p>比例本身是卡面数值（通道折好的快照读出来），引擎不替内容侧决定多少。</p>
 */
public final class Lifesteal {

    private Lifesteal() {
    }

    /** 这一发该回复多少。比例为 0、或这发一点没落到身上（含被打成 0）时回 0。 */
    public static double amount(@Nullable MechanicProfile attacker, double dealt) {
        if (attacker == null || !(dealt > 0.0)) {
            return 0.0;
        }
        double rate = Math.max(0.0, attacker.channel("lifesteal"));
        return rate <= 0.0 ? 0.0 : dealt * rate;
    }
}
