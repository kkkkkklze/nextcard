package com.klze.nextcard.logic;

import com.klze.nextcard.core.effect.Lifesteal;
import com.klze.nextcard.core.effect.MechanicProfile;
import com.klze.nextcard.core.effect.Mechanics;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 吸血算式（{@code channel.lifesteal}）的口径：<b>基数是<em>真的落到目标身上</em>的那个数</b>，
 * 与 {@code damage_dealt} 同一条律；被免疫成 0 的那发不算伤害，也就不算吸血。
 *
 * <p>另一作者的实现是 {@code total × lifesteal}（结算前的量）。两种都自洽，但只能有一份真相，
 * 所以整个口径收在这一个函数里——要换口径改这里，不要散在调用点。</p>
 */
public class LifestealTest {

    private static MechanicProfile channels(double lifesteal) {
        return new MechanicProfile(Map.of(
                "channel.lifesteal", new Mechanics.Folded(lifesteal, false, "")));
    }

    /** 落到 10 点、比例 15% ⇒ 回复 1.5。 */
    @Test
    public void theHealIsTheRateTimesWhatActuallyLanded() {
        assertEquals(1.5, Lifesteal.amount(channels(0.15), 10.0), 1e-9);
        assertEquals(0.45, Lifesteal.amount(channels(0.15), 3.0), 1e-9,
                "被减伤打到 3 点就按 3 算——基数跟着最终值走，不跟出手的量走");
    }

    /** 一点没落到身上（被打成 0、或压根没有这一发）就没有可吸的。 */
    @Test
    public void nothingLandsNothingIsDrained() {
        assertEquals(0.0, Lifesteal.amount(channels(0.15), 0.0), 1e-9, "免疫成 0 的那发不算吸血");
        assertEquals(0.0, Lifesteal.amount(channels(0.15), -2.0), 1e-9, "负数更不是'吸出伤害'");
        assertEquals(0.0, Lifesteal.amount(null, 10.0), 1e-9, "攻击者没有快照时不参与");
    }

    /** 没写这条通道＝比例 0，而不是"按某个默认比例吸"。 */
    @Test
    public void anAbsentChannelIsZeroNotSomeDefault() {
        assertEquals(0.0, Lifesteal.amount(new MechanicProfile(Map.of()), 10.0), 1e-9,
                "出厂不该有人偷偷吸血");
    }

    /** 卡面把比例写成负数是笔误：夹在 0，绝不让"吸血"变成"打自己"。 */
    @Test
    public void aNegativeRateIsClampedInsteadOfHealingTheTarget() {
        assertEquals(0.0, Lifesteal.amount(channels(-0.5), 10.0), 1e-9);
    }

    /** 比例不设上限（那是内容侧的数值决定），所以 100% 以上照样按乘算出来。 */
    @Test
    public void theRateHasNoCeilingBecauseThatIsAContentDecision() {
        assertEquals(30.0, Lifesteal.amount(channels(3.0), 10.0), 1e-9,
                "引擎不替内容侧决定多少——能不能回这么多是原版 heal 的夹子（生命上限）管的");
    }
}
