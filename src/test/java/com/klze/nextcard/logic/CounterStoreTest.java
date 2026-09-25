package com.klze.nextcard.logic;

import com.klze.nextcard.core.effect.CounterStore;
import com.klze.nextcard.core.effect.StackClause;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 层数账本（{@code core/effect/CounterStore}）的纯逻辑断言。
 * 这些断言只在"值"这一层，声明侧的折叠由 ParryChargeVocabularyTest 覆盖，两处不互抄。
 */
public class CounterStoreTest {

    private static final CounterStore.Key BARRIER = new CounterStore.Key("holder-a", "barrier");
    private static final CounterStore.Key RAGE = new CounterStore.Key("holder-b", "rage");

    /** 上限夹住产出，且返回值是"实际收下"的数量（溢出部分要能被上层用来做转化）。 */
    @Test
    public void gainIsClampedByCapAndReportsWhatItAccepted() {
        CounterStore store = new CounterStore();
        CounterStore.Rule cap3 = new CounterStore.Rule(3, 0.0, CounterStore.Expiry.PER_LAYER);

        assertEquals(2.0, store.gain(BARRIER, 2, cap3, 0.0), 1e-9);
        assertEquals(1.0, store.gain(BARRIER, 5, cap3, 1.0), 1e-9, "only one slot left under the cap");
        assertEquals(3.0, store.amount(BARRIER), 1e-9);
        assertEquals(0.0, store.gain(BARRIER, 4, cap3, 2.0), 1e-9, "full ledger accepts nothing");

        CounterStore.Rule unbounded = new CounterStore.Rule(StackClause.UNBOUNDED, 0.0, CounterStore.Expiry.PER_LAYER);
        assertEquals(4.0, store.gain(BARRIER, 4, unbounded, 3.0), 1e-9, "unbounded cap takes everything");
        assertEquals(7.0, store.amount(BARRIER), 1e-9);
    }

    /** 每层各计时：先到的先掉，掉多少算多少。 */
    @Test
    public void perLayerExpiryDropsLayersOneByOne() {
        CounterStore store = new CounterStore();
        CounterStore.Rule sixSeconds = new CounterStore.Rule(10, 6.0, CounterStore.Expiry.PER_LAYER);
        store.gain(BARRIER, 1, sixSeconds, 0.0);
        store.gain(BARRIER, 1, sixSeconds, 1.0);
        store.gain(BARRIER, 1, sixSeconds, 2.0);
        assertEquals(3.0, store.amount(BARRIER), 1e-9);

        assertEquals(1, store.expire(BARRIER, 6.5), "the 0.0 layer is gone at 6.5s");
        assertEquals(2.0, store.amount(BARRIER), 1e-9);
        assertEquals(2, store.expire(BARRIER, 8.5));
        assertEquals(0.0, store.amount(BARRIER), 1e-9);
        assertFalse(store.snapshot(8.5).containsKey("holder-a"), "empty ledger must not appear in the snapshot");
    }

    /** 整体续期：再产出就把整份账重置计时（"1.5 秒不攻击清零"那一类）。 */
    @Test
    public void refreshAllExpiryRestartTheWholeTimer() {
        CounterStore store = new CounterStore();
        CounterStore.Rule refresh15 = new CounterStore.Rule(10, 1.5, CounterStore.Expiry.REFRESH_ALL);
        store.gain(RAGE, 4, refresh15, 0.0);
        store.gain(RAGE, 2, refresh15, 1.0);
        assertEquals(6.0, store.amount(RAGE), 1e-9);

        assertEquals(0, store.expire(RAGE, 2.0), "the second gain re-timed all six layers");
        assertEquals(6, store.expire(RAGE, 2.6), "all of them expire 1.5s after the last gain");
        assertEquals(0.0, store.amount(RAGE), 1e-9);
    }

    /** 时长为 0 表示永久；消费先到先失，且账本永不为负。 */
    @Test
    public void permanentLayersAndSpendNeverGoNegative() {
        CounterStore store = new CounterStore();
        CounterStore.Rule forever = new CounterStore.Rule(StackClause.UNBOUNDED, 0.0, CounterStore.Expiry.PER_LAYER);
        store.gain(BARRIER, 3, forever, 0.0);
        assertEquals(0, store.expire(BARRIER, 10_000.0), "duration 0 never expires");

        assertEquals(2.0, store.spend(BARRIER, 2), 1e-9);
        assertEquals(1.0, store.amount(BARRIER), 1e-9);
        assertEquals(1.0, store.spend(BARRIER, 5), 1e-9, "spending more than held only takes what is there");
        assertEquals(0.0, store.amount(BARRIER), 1e-9);
        assertEquals(0.0, store.spend(BARRIER, 1), 1e-9);
    }

    /** 快照是"层数 + 最早剩余秒"两份事实，且互不串号（按持有者分组）。 */
    @Test
    public void snapshotCarriesLayersAndRemainingTimePerHolder() {
        CounterStore store = new CounterStore();
        CounterStore.Rule timed = new CounterStore.Rule(5, 4.0, CounterStore.Expiry.PER_LAYER);
        store.gain(BARRIER, 2, timed, 0.0);
        store.gain(RAGE, 1, timed, 1.0);

        Map<String, Map<String, CounterStore.Held>> snapshot = store.snapshot(1.0);
        assertEquals(2.0, snapshot.get("holder-a").get("barrier").layers(), 1e-9);
        assertEquals(3.0, snapshot.get("holder-a").get("barrier").remainingSeconds(), 1e-9, "gained at 0.0, 4s long, now 1.0");
        assertEquals(1.0, snapshot.get("holder-b").get("rage").layers(), 1e-9);
        assertEquals(4.0, snapshot.get("holder-b").get("rage").remainingSeconds(), 1e-9);

        store.clearHeld("holder-a");
        assertTrue(store.snapshot(1.0).containsKey("holder-b"), "clearing one holder must not touch another");
        assertFalse(store.snapshot(1.0).containsKey("holder-a"));
        assertEquals(0.0, store.amount(BARRIER), 1e-9);
        assertEquals(1.0, store.amount(RAGE), 1e-9);
    }

    /** 同一笔产出重复调用不会把上限之外的部分偷偷记进账本（幂等性由调用方保证，这里守住值不越界）。 */
    @Test
    public void amountNeverExceedsTheCapInForce() {
        CounterStore store = new CounterStore();
        CounterStore.Rule cap3 = new CounterStore.Rule(3, 0.0, CounterStore.Expiry.PER_LAYER);
        for (int i = 0; i < 20; i++) {
            store.gain(BARRIER, 2, cap3, i);
        }
        assertEquals(3.0, store.amount(BARRIER), 1e-9);

        CounterStore.Rule tightened = new CounterStore.Rule(1, 0.0, CounterStore.Expiry.PER_LAYER);
        assertEquals(0.0, store.gain(BARRIER, 1, tightened, 30.0), 1e-9,
                "a tightened cap blocks gains but must not silently truncate existing layers");
        assertEquals(3.0, store.amount(BARRIER), 1e-9);
    }
}
