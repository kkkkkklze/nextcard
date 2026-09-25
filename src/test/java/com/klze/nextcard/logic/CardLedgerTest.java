package com.klze.nextcard.logic;

import com.klze.nextcard.core.player.CardLedger;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 玩家卡账（{@code core/player/CardLedger}）：只存 id、未知 id 不许掉、顺序确定。 */
public class CardLedgerTest {

    private static final ResourceLocation A = new ResourceLocation("nextcard", "a");
    private static final ResourceLocation B = new ResourceLocation("nextcard", "b");

    @Test
    public void holdingIsIdBasedAndIdempotent() {
        CardLedger ledger = new CardLedger();
        assertTrue(ledger.grant(A));
        assertFalse(ledger.grant(A), "重复授予不能改变账本（否则重算会叠加）");
        assertEquals(1, ledger.size());
        assertTrue(ledger.owns(A));
        assertTrue(ledger.revoke(A));
        assertFalse(ledger.revoke(A), "撤一张没有的卡必须回false（没改动）");
    }

    /** 顺序 = 授予顺序，不是哈希序：同步包与"第 N 张"这类账本都依赖它。 */
    @Test
    public void orderFollowsGrantingNotHashing() {
        CardLedger ledger = new CardLedger();
        for (int i = 0; i < 12; i++) {
            ledger.grant(new ResourceLocation("nextcard", "card_" + i));
        }
        assertEquals(List.of("nextcard:card_0", "nextcard:card_1", "nextcard:card_2"),
                ledger.ownedAsText().subList(0, 3));
    }

    /**
     * 内容包降级/换版本时卡会暂时查不到。抹掉它等于偷玩家的卡，且重算再也长不回来——
     * 所以必须原样保留并单独上报。
     */
    @Test
    public void unknownIdsAreKeptAndReported() {
        List<String> saved = List.of("nextcard:a", "nextcard:ghost", "not a location");
        CardLedger ledger = CardLedger.fromText(saved, 7, Map.of("counter", 3), Set.of(A));

        assertEquals(3, ledger.size(), "查不到的那张也要留在账上");
        assertEquals(List.of("nextcard:ghost", "not a location"), ledger.unknownOnLoad());
        assertEquals(7, ledger.drawCount());
        assertEquals(3, ledger.counter("counter"));

        // 内容回来之后不应出现"同一张卡两份"
        CardLedger again = CardLedger.fromText(ledger.ownedAsText(), ledger.drawCount(),
                ledger.countersAsText(), Set.of(A, new ResourceLocation("nextcard", "ghost")));
        assertFalse(again.unknownOnLoad().contains("nextcard:a"));
        assertEquals(3, again.size());
    }

    @Test
    public void countersMergeAndRefundNeverGoesNegative() {
        CardLedger ledger = new CardLedger();
        ledger.grant(A);
        ledger.recordDraw();
        ledger.recordDraw();
        assertEquals(2, ledger.drawCount());
        ledger.refundDraw();
        ledger.refundDraw();
        ledger.refundDraw();
        assertEquals(0, ledger.drawCount(), "退卡机会不能退成负数");

        ledger.addCounter("fever", 3);
        ledger.addCounter("fever", -1);
        assertEquals(2, ledger.counter("fever"));
        ledger.clearCounter("fever");
        assertEquals(0, ledger.counter("fever"));
    }
}
