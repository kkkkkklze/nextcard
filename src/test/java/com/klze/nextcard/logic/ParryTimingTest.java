package com.klze.nextcard.logic;

import com.klze.nextcard.core.effect.Facts;
import com.klze.nextcard.core.effect.MechanicProfile;
import com.klze.nextcard.core.effect.ModifierClause;
import com.klze.nextcard.core.effect.ParryTiming;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 盾反时机判定：窗口怎么合成、什么算精准、以及那条原版下限造成的死区。 */
public class ParryTimingTest {

    private static MechanicProfile fold(List<ModifierClause> modifiers) {
        return MechanicProfile.fold(modifiers, id -> 0);
    }

    /** 延长项加法、乘区乘法、难度除法——公式只有 {@link com.klze.nextcard.core.effect.WindowMath} 那一份。 */
    @Test
    public void theWindowUsesTheSameLawAsTheSolvers() {
        MechanicProfile profile = fold(List.of(
                new ModifierClause("window.length", 0.5, "", 0.0, List.of()),
                new ModifierClause("window.scale", 2.0, "", 0.0, List.of()),
                new ModifierClause("window.difficulty", 2.0, "", 0.0, List.of())));
        assertEquals(0.6, ParryTiming.windowSeconds(0.4, profile), 1e-9, "0.4 ×1.5 ×2 ÷2");
        assertEquals(0.4, ParryTiming.windowSeconds(0.4, fold(List.of())), 1e-9, "没有改写就是声明值");

        // 难度项的方向必须钉住：写 ÷2 是让窗口<em>变短</em>（更难），不是变长——
        // 折叠已经把除数落进去了，读的时候再除一次就会反向（实测得到 2.4 的那次）
        MechanicProfile harder = fold(List.of(new ModifierClause("window.difficulty", 2.0, "", 0.0, List.of())));
        assertEquals(0.2, ParryTiming.windowSeconds(0.4, harder), 1e-9);
        assertTrue(ParryTiming.windowSeconds(0.4, harder) < 0.4, "加了难度项之后窗口只会更短");
    }

    /** 精准区间是 [原版那 5 tick, 窗口]，两端都闭合。 */
    @Test
    public void thePreciseRangeStartsWhereVanillaStartsCounting() {
        double window = 0.4;  // 8 tick
        assertFalse(ParryTiming.precise(4, window), "举盾 4 tick 原版还不算在挡，谈不上精准");
        assertTrue(ParryTiming.precise(ParryTiming.VANILLA_BLOCK_TICKS, window));
        assertTrue(ParryTiming.precise(8, window));
        assertFalse(ParryTiming.precise(9, window), "出窗了");
    }

    /**
     * 窗口比原版下限还短 ⇒ 结构性打不出来。这条不是挑刺：内容侧写"窗口 0.1 秒"那种卡时，
     * 报错应该在裁定阶段发生，而不是让玩家以为自己有盾反能力。
     */
    @Test
    public void aWindowShorterThanTheVanillaFloorCanNeverTriggerAParry() {
        assertFalse(ParryTiming.precise(5, 0.1), "0.1 秒 = 2 tick < 5 tick 下限");
        assertFalse(ParryTiming.precise(2, 0.1));
        assertTrue(ParryTiming.windowTicks(0.1) < ParryTiming.VANILLA_BLOCK_TICKS,
                "死区存在这件事本身也要能被断言到");
    }

    /** 判定读的是事实里的开关，不读实体：世界侧那一半由 DamageContact 负责喂进来。 */
    @Test
    public void blockingIsAFactNotAnEntityCall() {
        assertFalse(Facts.NONE.flag("blocking"));
        assertTrue(Facts.builder().with("blocking").build().flag("blocking"));
    }
}
