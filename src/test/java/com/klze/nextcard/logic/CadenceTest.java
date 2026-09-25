package com.klze.nextcard.logic;

import com.klze.nextcard.core.effect.Cadence;
import com.klze.nextcard.core.effect.TriggerClause;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 周期调度（{@code core/effect/Cadence}）的纯逻辑断言：内容只写秒，引擎只在这里换算成 tick。
 */
public class CadenceTest {

    /** 「1 刻 = 0.05 秒」这条口径必须正好落成一格 tick。 */
    @Test
    public void secondsConvertToTicksAtOneCanonicalPlace() {
        assertEquals(1, Cadence.ticksOf(0.05), "一刻就是一 tick");
        assertEquals(20, Cadence.ticksOf(1.0));
        assertEquals(120, Cadence.ticksOf(6.0));
        assertEquals(1, Cadence.ticksOf(0.001), "亚 tick 的节奏抬到一 tick，不能变成从不触发");
        assertThrows(IllegalArgumentException.class, () -> Cadence.ticksOf(0.0));
        assertThrows(IllegalArgumentException.class, () -> Cadence.ticksOf(-1.0));
    }

    /** 第一个周期到点前不触发；到点那一 tick 恰好触发一次，不提前也不重复。 */
    @Test
    public void firstFireLandsExactlyOnThePeriod() {
        int period = Cadence.ticksOf(2.0);  // 40 tick
        assertEquals(0L, Cadence.dueCount(period, 100, 139), "39 tick in: nothing owed");
        assertFalse(Cadence.isDue(period, 100, 139));
        assertTrue(Cadence.isDue(period, 100, 140), "40 tick in: due");
        assertFalse(Cadence.isDue(period, 100, 141), "must not fire twice for one period");
        assertEquals(1L, Cadence.dueCount(period, 100, 141));
        assertEquals(3L, Cadence.dueCount(period, 100, 220));
    }

    /** 掉帧/跳过 tick 不追补：卡了 100 tick 也只欠一次。 */
    @Test
    public void skippedTicksDoNotAccomulateCatchUpFires() {
        int period = 20;
        long onset = 1000;
        long after = dueCountAcross(period, onset, onset + 100);
        assertEquals(5L, after, "100 tick 跨过 5 个周期");
        // 逐 tick 累加应当与整段算一次给出同一个数（没有漂移）
        long stepped = 0;
        for (long tick = onset + 1; tick <= onset + 100; tick++) {
            if (Cadence.isDue(period, onset, tick)) {
                stepped++;
            }
        }
        assertEquals(after, stepped, "整段计算与逐 tick 计算必须一致，否则重启/补帧会改玩家可见的节奏");
    }

    /** 同一 tick 多个周期：顺序按字典序定死，且互不重复。 */
    @Test
    public void dueKeysAreDeterministicallyOrdered() {
        Map<String, Double> declared = Map.of(
                "zeal", 2.0,
                "bleed", 1.0,
                "miasma", 2.0,
                "instant", 0.5);
        List<String> due = Cadence.dueKeys(declared, 0, 40);
        assertEquals(List.of("bleed", "instant", "miasma", "zeal"), due,
                "四个都到点（0.5/1/2/2 秒都整除 40 tick）· 按字典序 · 无重复");
        assertTrue(Cadence.dueKeys(declared, 0, 5).isEmpty(), "第 5 tick：最快的 0.5 秒（10 tick）还没到");
    }

    /** 声明侧：{@code every} 是 trigger 的可选键，写错数值不被当成没写。 */
    @Test
    public void everyKeyParsesIntoTheTriggerAndRejectsNonPositive() {
        List<String> errors = new java.util.ArrayList<>();
        TriggerClause periodic = TriggerClause.parse(obj(
                "{\"type\":\"trigger\",\"on\":\"tick\",\"every\":1.5,"
                        + "\"actions\":[{\"stun\":{\"seconds\":1}}]}"), errors);
        assertTrue(errors.isEmpty(), "合法 every 不应报错: " + errors);
        assertTrue(periodic.periodic());
        assertEquals(30, periodic.periodTicks());

        List<String> bad = new java.util.ArrayList<>();
        TriggerClause rejected = TriggerClause.parse(obj(
                "{\"type\":\"trigger\",\"on\":\"tick\",\"every\":0,"
                        + "\"actions\":[{\"stun\":{\"seconds\":1}}]}"), bad);
        assertFalse(bad.isEmpty(), "every:0 必须报错，不能静默变成非周期");
        assertTrue(rejected == null);
    }

    private static long dueCountAcross(int period, long onset, long now) {
        return Cadence.dueCount(period, onset, now);
    }

    private static com.google.gson.JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
