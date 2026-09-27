package com.klze.nextcard.logic;

import com.google.gson.JsonParser;
import com.klze.nextcard.core.effect.CounterStore;
import com.klze.nextcard.core.effect.Facts;
import com.klze.nextcard.core.effect.TriggerClause;
import com.klze.nextcard.core.effect.Triggers;
import com.klze.nextcard.core.load.ContentReader;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 触发执行器：动作词表里的 {@code lethal_immunity} 第一次真正被消费。
 *
 * <p>这里盯的是两类会伪装成"正常"的错：不致命的挨打把冷却白扣掉（真该活下来时没免疫），
 * 以及两张卡共用一份账。所以每条断言都同时看<em>兑现结果</em>和<em>账本余量</em>。</p>
 */
public class TriggersTest {

    private static final String HOLDER = "player-1";

    private static Triggers.Bound bound(String path, String json) {
        List<String> errors = new ArrayList<>();
        TriggerClause clause = (TriggerClause) TriggerClause.parse(
                JsonParser.parseString(json).getAsJsonObject(), errors);
        assertTrue(errors.isEmpty(), json + " 应能解析: " + errors);
        assertNotNull(clause);
        return new Triggers.Bound(new ResourceLocation("nextcard", path), clause);
    }

    private static Triggers.Bound immunity(String path) {
        return bound(path, "{\"type\":\"trigger\",\"on\":\"damage_taken\",\"when\":[{\"fatal\":true}],"
                + " \"actions\":[{\"lethal_immunity\":{\"uses\":1,\"cooldown\":20}}]}");
    }

    private static Facts fatal(double ownHpRatio) {
        return Facts.builder().attackerHp(ownHpRatio).with("fatal").build();
    }

    /** 兑现一次 → 进冷却 → 冷却到点又能兑现。 */
    @Test
    public void oneChargeTwentySecondsAndThenAgain() {
        CounterStore counters = new CounterStore();
        List<Triggers.Bound> bound = List.of(immunity("last_stand"));

        Triggers.Firing first = Triggers.lethalImmunity(HOLDER, fatal(0.7), bound, counters, 0.0);
        assertNotNull(first);
        assertTrue(first.reason().contains("免疫这一发"), first.reason());
        assertEquals(1.0, Triggers.inFlight(counters, HOLDER, 0.0), "用掉一次就该有账在途");

        assertNull(Triggers.lethalImmunity(HOLDER, fatal(0.7), bound, counters, 19.9), "冷却在途时不能再免疫");
        assertNotNull(Triggers.lethalImmunity(HOLDER, fatal(0.7), bound, counters, 20.1), "到点就该恢复");
    }

    /** 不致命：<b>连账本都不许碰</b>（否则一次普攻就把救命的那次扣掉了）。 */
    @Test
    public void aNonFatalHitSpendsNothing() {
        CounterStore counters = new CounterStore();
        List<Triggers.Bound> bound = List.of(immunity("last_stand"));
        Facts grazed = Facts.builder().attackerHp(0.9).build();

        assertNull(Triggers.lethalImmunity(HOLDER, grazed, bound, counters, 0.0));
        assertEquals(0.0, Triggers.inFlight(counters, HOLDER, 0.0));
        assertNotNull(Triggers.lethalImmunity(HOLDER, fatal(0.9), bound, counters, 0.0),
                "上一句不能把冷却已经扣掉的路径伪装成'没扣'");
    }

    /** 卡面条件（when）没过时同样什么都不消费——包括 {@code uses > 1} 那张。 */
    @Test
    public void whenGateRunsBeforeTheLedger() {
        CounterStore counters = new CounterStore();
        Triggers.Bound lowHpOnly = bound("desperate", "{\"type\":\"trigger\",\"on\":\"damage_taken\","
                + " \"when\":[{\"hp_below\": 0.3}, {\"any_of\": [{\"fatal\": true},"
                + " {\"target_kind\": \"boss\"}]}],"
                + " \"actions\":[{\"lethal_immunity\":{\"uses\":2,\"cooldown\":5}}]}");

        assertNull(Triggers.lethalImmunity(HOLDER, fatal(0.6), List.of(lowHpOnly), counters, 0.0),
                "血量 60% 不满足 hp_below 0.3");
        assertEquals(0.0, Triggers.inFlight(counters, HOLDER, 0.0));

        assertNotNull(Triggers.lethalImmunity(HOLDER, fatal(0.2), List.of(lowHpOnly), counters, 0.0),
                "残血 + 致命：or 分支里第一条就成立");
        assertEquals(1.0, Triggers.inFlight(counters, HOLDER, 0.0), "uses=2 时先扣一层");
        assertNotNull(Triggers.lethalImmunity(HOLDER, fatal(0.2), List.of(lowHpOnly), counters, 1.0));
        assertNull(Triggers.lethalImmunity(HOLDER, fatal(0.2), List.of(lowHpOnly), counters, 2.0),
                "两层都用完了");
    }

    /** 两张卡各自一份账：第一张兑现后，第二张仍然救得回来。 */
    @Test
    public void eachCardKeepsItsOwnLedger() {
        CounterStore counters = new CounterStore();
        List<Triggers.Bound> bound = List.of(immunity("a_second"), immunity("a_first"));

        Triggers.Firing first = Triggers.lethalImmunity(HOLDER, fatal(0.5), bound, counters, 0.0);
        assertNotNull(first);
        assertEquals(new ResourceLocation("nextcard", "a_first"), first.cardId(), "顺序要稳定，不能看哈希遍历");
        assertEquals(1.0, Triggers.inFlight(counters, HOLDER, 0.0));

        Triggers.Firing second = Triggers.lethalImmunity(HOLDER, fatal(0.5), bound, counters, 0.0);
        assertNotNull(second, "另一张卡的次数不该被前一张带走");
        assertEquals(new ResourceLocation("nextcard", "a_second"), second.cardId());
        assertEquals(2.0, Triggers.inFlight(counters, HOLDER, 0.0));
    }

    /** 事件不对就不该理（"免疫"只挂在 damage_taken 上）。 */
    @Test
    public void onlyTheEventItIsBoundToCanFireIt() {
        CounterStore counters = new CounterStore();
        Triggers.Bound onAttack = bound("reckless", "{\"type\":\"trigger\",\"on\":\"attack\","
                + " \"when\":[{\"fatal\": true}],\"actions\":[{\"lethal_immunity\":{\"uses\":1}}]}");
        assertNull(Triggers.lethalImmunity(HOLDER, fatal(0.2), List.of(onAttack), counters, 0.0));
        assertEquals(0.0, Triggers.inFlight(counters, HOLDER, 0.0));
    }

    /** 引擎命名空间是预留的：内容用 {@code trigger.} 开叠层会把冷却与资源记在同一本账上。 */
    @Test
    public void theEngineNamespaceIsReserved() {
        assertTrue(Triggers.isReservedStackId(Triggers.COOLDOWN_PREFIX + "last_stand"));
        assertTrue(!Triggers.isReservedStackId("barrier"));
        ResourceLocation card = new ResourceLocation("nextcard", "last_stand");
        CounterStore.Key key = Triggers.cooldown(HOLDER, card,
                new com.klze.nextcard.core.effect.Action("lethal_immunity",
                        JsonParser.parseString("{\"uses\":1}").getAsJsonObject()));
        assertEquals(HOLDER, key.holder(), "持有者必须进键，否则全服务器共用一份冷却");
        assertTrue(key.resource().startsWith(Triggers.COOLDOWN_PREFIX));
        assertTrue(key.resource().contains("last_stand"));
    }

    /** 致命口径只有一处：血量减这一发 ≤ 0（原版算完护甲之后的数）。 */
    @Test
    public void fatalMeansThisHitWouldFinishMe() {
        assertTrue(Triggers.isFatal(10.0, 10.0), "正好打死也算致命");
        assertTrue(Triggers.isFatal(10.0, 10.5));
        assertTrue(!Triggers.isFatal(10.0, 9.9));
    }

    /**
     * 预留命名空间那条闸门要在<b>真实加载路径</b>上会红，不能只让 {@code isReservedStackId}
     * 自己说自己对（内容占用引擎前缀会把"壁障"和"免疫冷却"记到同一本账上）。
     */
    @Test
    public void theEngineNamespaceGateFailsOnTheRealLoadPath() {
        var tags = ContentReader.readTags(Map.of("nextcard:card_tags/attack.json",
                json("{\"name\": \"tag.nextcard.attack\"}")));
        assertTrue(tags.ok(), tags.errors().toString());

        var hijack = ContentReader.readCards(Map.of("nextcard:cards/hijack.json",
                json("{\"tier\": 3, \"card_class\": \"B\", \"tags\": [\"nextcard:attack\"],"
                        + " \"effects\": [{\"type\": \"stacks\", \"id\": \"trigger.cd\", \"cap\": 2}]}")),
                tags.value());
        assertTrue(!hijack.ok(), "内容占用引擎命名空间必须是加载错误");
        assertTrue(hijack.errors().toString().contains("engine namespace"), hijack.errors().toString());
    }

    private static com.google.gson.JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
