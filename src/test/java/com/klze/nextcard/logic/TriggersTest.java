package com.klze.nextcard.logic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.klze.nextcard.core.card.CardIndex;
import com.klze.nextcard.core.effect.CounterStore;
import com.klze.nextcard.core.effect.EffectHost;
import com.klze.nextcard.core.effect.Facts;
import com.klze.nextcard.core.effect.MechanicProfile;
import com.klze.nextcard.core.effect.Settlement;
import com.klze.nextcard.core.effect.StackClause;
import com.klze.nextcard.core.effect.TriggerClause;
import com.klze.nextcard.core.effect.Triggers;
import com.klze.nextcard.core.load.ContentReader;
import com.klze.nextcard.core.load.LoadResult;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 触发执行器：动作词表里的 {@code lethal_immunity} 与 {@code stacks} 真的被消费了。
 *
 * <p>这里盯的是两类会伪装成"正常"的错：不致命的挨打把冷却白扣掉（真该活下来时没免疫），
 * 以及层数变了但快照还停在旧值上（玩家看到"层数在涨、减伤没动"）。所以每条断言都同时看
 * <em>兑现结果</em>、<em>账本余量</em>和<em>重折之后的通道值</em>。</p>
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

    private static Triggers.Result fire(String event, Facts facts, List<Triggers.Bound> bound,
                                       CounterStore counters, Map<String, StackClause> declared,
                                       MechanicProfile profile, double now) {
        return fire(event, facts, bound, counters, declared, profile, Triggers.Bases.NONE, now);
    }

    private static Triggers.Result fire(String event, Facts facts, List<Triggers.Bound> bound,
                                       CounterStore counters, Map<String, StackClause> declared,
                                       MechanicProfile profile, Triggers.Bases bases, double now) {
        return Triggers.fire(event, HOLDER, facts, bound, counters, declared, profile, bases, now);
    }

    private static Facts fatal(double ownHpRatio) {
        return Facts.builder().attackerHp(ownHpRatio).with("fatal").build();
    }

    /** 兑现一次 → 进冷却 → 冷却到点又能兑现。 */
    @Test
    public void oneChargeTwentySecondsAndThenAgain() {
        CounterStore counters = new CounterStore();
        List<Triggers.Bound> bound = List.of(immunity("last_stand"));

        Triggers.Result first = fire(Triggers.DAMAGE_TAKEN, fatal(0.7), bound, counters, Map.of(), null, 0.0);
        assertNotNull(first.vetoReason());
        assertTrue(first.vetoReason().contains("免疫"), first.vetoReason());
        assertEquals(1, first.fired().size());
        assertEquals(1.0, Triggers.inFlight(counters, HOLDER, 0.0), "用掉一次就该有账在途");

        assertNull(fire(Triggers.DAMAGE_TAKEN, fatal(0.7), bound, counters, Map.of(), null, 19.9).vetoReason(),
                "冷却在途时不能再免疫");
        assertNotNull(fire(Triggers.DAMAGE_TAKEN, fatal(0.7), bound, counters, Map.of(), null, 20.1).vetoReason(),
                "到点就该恢复");
    }

    /** 不致命：连账本都不许碰（否则一次普攻就把救命那次扣掉了）。 */
    @Test
    public void aNonFatalHitSpendsNothing() {
        CounterStore counters = new CounterStore();
        List<Triggers.Bound> bound = List.of(immunity("last_stand"));

        Triggers.Result grazed = fire(Triggers.DAMAGE_TAKEN, Facts.NONE, bound, counters, Map.of(), null, 0.0);
        assertNull(grazed.vetoReason());
        assertEquals(0.0, Triggers.inFlight(counters, HOLDER, 0.0));
        assertTrue(grazed.fired().isEmpty(), grazed.fired().toString());
        assertNotNull(fire(Triggers.DAMAGE_TAKEN, fatal(0.9), bound, counters, Map.of(), null, 0.0).vetoReason(),
                "上一句不许把「根本没扣」伪装成「扣了也没事」");
    }

    /** 卡面条件（when）先于账本：不成立时既不兑现也不消费。 */
    @Test
    public void whenGateRunsBeforeTheLedger() {
        CounterStore counters = new CounterStore();
        Triggers.Bound lowHpOnly = bound("desperate", "{\"type\":\"trigger\",\"on\":\"damage_taken\","
                + " \"when\":[{\"hp_below\": 0.3}, {\"any_of\": [{\"fatal\": true},"
                + " {\"target_kind\": \"boss\"}]}],"
                + " \"actions\":[{\"lethal_immunity\":{\"uses\":2,\"cooldown\":5}}]}");
        List<Triggers.Bound> bound = List.of(lowHpOnly);

        Triggers.Result rich = fire(Triggers.DAMAGE_TAKEN, fatal(0.6), bound, counters, Map.of(), null, 0.0);
        assertNull(rich.vetoReason(), "血量 60% 不满足 hp_below 0.3");
        assertEquals(0.0, Triggers.inFlight(counters, HOLDER, 0.0));

        assertNotNull(fire(Triggers.DAMAGE_TAKEN, fatal(0.2), bound, counters, Map.of(), null, 0.0).vetoReason(),
                "残血 + 致命：or 分支里第一条就成立");
        assertEquals(1.0, Triggers.inFlight(counters, HOLDER, 0.0), "uses=2 时先扣一层");
        assertNotNull(fire(Triggers.DAMAGE_TAKEN, fatal(0.2), bound, counters, Map.of(), null, 1.0).vetoReason());
        assertNull(fire(Triggers.DAMAGE_TAKEN, fatal(0.2), bound, counters, Map.of(), null, 2.0).vetoReason(),
                "两层都用完了");
    }

    /** 两张卡各自一份账：第一张兑现后，第二张仍然救得回来。 */
    @Test
    public void eachCardKeepsItsOwnLedger() {
        CounterStore counters = new CounterStore();
        List<Triggers.Bound> bound = List.of(immunity("a_second"), immunity("a_first"));

        Triggers.Result first = fire(Triggers.DAMAGE_TAKEN, fatal(0.5), bound, counters, Map.of(), null, 0.0);
        assertTrue(first.vetoReason().contains("a_first"), "顺序要稳定，不能看哈希遍历: " + first.vetoReason());
        assertEquals(1.0, Triggers.inFlight(counters, HOLDER, 0.0));

        Triggers.Result second = fire(Triggers.DAMAGE_TAKEN, fatal(0.5), bound, counters, Map.of(), null, 0.0);
        assertNotNull(second.vetoReason(), "另一张卡的次数不该被前一张带走");
        assertTrue(second.vetoReason().contains("a_second"), second.vetoReason());
        assertEquals(2.0, Triggers.inFlight(counters, HOLDER, 0.0));
    }

    /** 事件不对就不该理：绑在 hit 上的子句不会被 damage_taken 叫醒（反之亦然）。 */
    @Test
    public void onlyTheEventItIsBoundToCanFireIt() {
        CounterStore counters = new CounterStore();
        Triggers.Bound onHit = bound("reckless", "{\"type\":\"trigger\",\"on\":\"hit\","
                + " \"actions\":[{\"stacks\":{\"id\":\"wall\",\"amount\":1}}]}");
        List<Triggers.Bound> bound = List.of(onHit);

        assertEquals(0, fire(Triggers.DAMAGE_TAKEN, Facts.NONE, bound, counters,
                Map.of("wall", stack("wall", 3, 0)), null, 0.0).fired().size(), "挨打不算命中");
        assertEquals(1, fire(Triggers.HIT, Facts.NONE, bound, counters,
                Map.of("wall", stack("wall", 3, 0)), null, 0.0).fired().size());
    }

    /** 叠层：按声明的上限夹住，到点掉层，consume 是扣而不是加负数。 */
    @Test
    public void stacksFollowTheDeclaredRuleAndExpireOnTheirOwnClock() {
        CounterStore counters = new CounterStore();
        Map<String, StackClause> declared = Map.of("wall", stack("wall", 2, 6));
        List<Triggers.Bound> bound = List.of(bound("wall_card", "{\"type\":\"trigger\",\"on\":\"hit\","
                + " \"actions\":[{\"stacks\":{\"id\":\"wall\",\"amount\":1}}]}"));
        List<Triggers.Bound> consume = List.of(bound("drain", "{\"type\":\"trigger\",\"on\":\"hit\","
                + " \"actions\":[{\"stacks\":{\"id\":\"wall\",\"amount\":2,\"consume\":true}}]}"));

        fire(Triggers.HIT, Facts.NONE, bound, counters, declared, null, 0.0);
        fire(Triggers.HIT, Facts.NONE, bound, counters, declared, null, 1.0);
        assertEquals(2.0, Triggers.layers(counters, HOLDER, "wall"), "声明上限 2，第三次也该夹在 2");
        fire(Triggers.HIT, Facts.NONE, bound, counters, declared, null, 2.0);
        assertEquals(2.0, Triggers.layers(counters, HOLDER, "wall"));

        counters.expire(new CounterStore.Key(HOLDER, "wall"), 7.0);
        assertEquals(0.0, Triggers.layers(counters, HOLDER, "wall"), "每层各计 6 秒，到点自己掉");

        fire(Triggers.HIT, Facts.NONE, bound, counters, declared, null, 8.0);
        fire(Triggers.HIT, Facts.NONE, bound, counters, declared, null, 8.0);
        assertEquals(2.0, Triggers.layers(counters, HOLDER, "wall"));
        fire(Triggers.HIT, Facts.NONE, consume, counters, declared, null, 9.0);
        assertEquals(0.0, Triggers.layers(counters, HOLDER, "wall"), "consume 是扣层，不是加负数");
    }

    /** {@code ignore_cap} 是唯一能把上限摘掉的写法（卡面"层数不再有上限"那类）。 */
    @Test
    public void ignoreCapIsTheOnlyWayPastTheDeclaredCeiling() {
        CounterStore counters = new CounterStore();
        Map<String, StackClause> declared = Map.of("wall", stack("wall", 1, 0));
        List<Triggers.Bound> capped = List.of(bound("wall_card", "{\"type\":\"trigger\",\"on\":\"hit\","
                + " \"actions\":[{\"stacks\":{\"id\":\"wall\",\"amount\":3}}]}"));
        List<Triggers.Bound> unbounded = List.of(bound("wall_card", "{\"type\":\"trigger\",\"on\":\"hit\","
                + " \"actions\":[{\"stacks\":{\"id\":\"wall\",\"amount\":3,\"ignore_cap\":true}}]}"));

        fire(Triggers.HIT, Facts.NONE, capped, counters, declared, null, 0.0);
        assertEquals(1.0, Triggers.layers(counters, HOLDER, "wall"), "没写 ignore_cap 就夹在上限上");

        counters.clearHeld(HOLDER);
        fire(Triggers.HIT, Facts.NONE, unbounded, counters, declared, null, 0.0);
        assertEquals(3.0, Triggers.layers(counters, HOLDER, "wall"), "写了才允许越过去");
    }

    /** 还没实现的动作用<em>报出来</em>，不许静默跳过。 */
    @Test
    public void actionsWithoutAnExecutorAreReportedNotSwallowed() {
        CounterStore counters = new CounterStore();
        List<Triggers.Bound> bound = List.of(bound("shockwave", "{\"type\":\"trigger\",\"on\":\"hit\","
                + " \"actions\":[{\"damage\":{\"basis\":\"armor\",\"coefficient\":0.5}},"
                + " {\"stacks\":{\"id\":\"wall\",\"amount\":1}}]}"));
        Triggers.Result result = fire(Triggers.HIT, Facts.NONE, bound, counters,
                Map.of("wall", stack("wall", 3, 0)), null, 0.0);

        assertEquals(1, result.unsupported().size(), result.unsupported().toString());
        assertTrue(result.unsupported().get(0).contains("damage"), result.unsupported().toString());
        assertEquals(1, result.fired().size(), "同一子句里能做的照做");
    }

    /**
     * 一整圈：内容 → 触发 → 层数 → 重新折叠 → 结算。
     * 这条是"层数变了要标脏"的证据：少了那一脚，玩家看到的就是"层数在涨、减伤没动"。
     */
    @Test
    public void layersGainedInPlayChangeTheNextSettlement() {
        CardIndex index = indexWith("wall_card.json", "{\"tier\": 3, \"card_class\": \"B\","
                + " \"tags\": [\"nextcard:attack\"], \"effects\": ["
                + " {\"type\": \"stacks\", \"id\": \"wall\", \"cap\": 3, \"duration\": 6},"
                + " {\"type\": \"trigger\", \"on\": \"hit\","
                + "  \"actions\": [{\"stacks\": {\"id\": \"wall\", \"amount\": 1}}]},"
                + " {\"type\": \"mechanic_modifier\", \"target\": \"channel.damage_reduction\","
                + "  \"source\": {\"counter\": \"wall\", \"per_layer\": 0.05}}]}");
        ResourceLocation cardId = new ResourceLocation("nextcard", "wall_card");
        EffectHost host = new EffectHost(() -> index);
        host.syncOwned(HOLDER, Set.of(cardId));
        host.flush(List.of());

        double reduction = host.profile(HOLDER).channel("damage_reduction");
        assertEquals(0.0, reduction, 1e-9, "一层都还没有");

        Triggers.Result fired = Triggers.fire(Triggers.HIT, HOLDER, Facts.NONE, host.triggers(HOLDER),
                host.counters(), host.declaredStacks(), host.profile(HOLDER), Triggers.Bases.NONE, 0.0);
        assertEquals(1, fired.fired().size(), fired.toString());
        assertEquals(1.0, Triggers.layers(host.counters(), HOLDER, "wall"));

        host.markDirty(HOLDER);
        host.flush(List.of());
        assertEquals(0.05, host.profile(HOLDER).channel("damage_reduction"), 1e-9,
                "每层 5% 减伤要在重折之后进到通道里");

        Settlement.Result hit = Settlement.resolve(Settlement.plain(100),
                new com.klze.nextcard.core.effect.DefencePipeline.Options(null, List.of(), List.of(),
                        List.of(new com.klze.nextcard.core.effect.DefencePipeline.Source("壁障",
                                host.profile(HOLDER).channel("damage_reduction")))));
        assertEquals(95.0, hit.value(), 1e-9, "这一发才是玩家看到的数");
    }

    /** 反弹：基数默认是"这一发的量"，目标固定是打我的那位（不搜半径）。 */
    @Test
    public void reflectGoesBackAtTheAttackerOnly() {
        CounterStore counters = new CounterStore();
        List<Triggers.Bound> bound = List.of(bound("echo_shell", "{\"type\":\"trigger\","
                + " \"on\":\"damage_taken\",\"actions\":[{\"reflect\":{\"ratio\":0.5}}]}"));
        Triggers.Result result = fire(Triggers.DAMAGE_TAKEN, Facts.NONE, bound, counters, Map.of(), null,
                new Triggers.Bases(0.0, 0.0, 12.0), 0.0);

        assertEquals(1, result.extraHits().size(), result.extraHits().toString());
        assertEquals(6.0, result.extraHits().get(0).amount(), 1e-9, "12 × 0.5");
        assertEquals(Triggers.Target.ATTACKER, result.extraHits().get(0).target());
        assertEquals(0.0, result.extraHits().get(0).radius(), 1e-9, "反弹不该顺手搜一圈");

        List<Triggers.Bound> zero = List.of(bound("echo_shell", "{\"type\":\"trigger\","
                + " \"on\":\"damage_taken\",\"actions\":[{\"reflect\":{\"ratio\":0.0}}]}"));
        Triggers.Result bounced = fire(Triggers.DAMAGE_TAKEN, Facts.NONE, zero, counters, Map.of(), null,
                new Triggers.Bases(0.0, 0.0, 12.0), 0.0);
        assertTrue(bounced.extraHits().isEmpty() && !bounced.unsupported().isEmpty(),
                "0 比例要说出来，不能静默不发: " + bounced.unsupported());
    }

    /** 强制精准：一枚在途计数、按时长过期；到点之后又要靠真窗口。 */
    @Test
    public void forceParryHoldsForItsSecondsThenLetsGo() {
        CounterStore counters = new CounterStore();
        List<Triggers.Bound> granter = List.of(bound("iron_root", "{\"type\":\"trigger\","
                + " \"on\":\"damage_taken\",\"actions\":[{\"force_parry\":{\"seconds\":3}}]}"));

        assertTrue(!Triggers.forcedPrecise(HOLDER, granter, counters, 0.0), "还没挨打就没有窗口");
        fire(Triggers.DAMAGE_TAKEN, Facts.NONE, granter, counters, Map.of(), null, 0.0);
        assertTrue(Triggers.forcedPrecise(HOLDER, granter, counters, 2.9), "2.9 秒还在窗口里");
        assertTrue(!Triggers.forcedPrecise(HOLDER, granter, counters, 3.1), "过点就该撒手");

        // 同一张卡反复触发只有一枚在途（cap 1），时长刷新成最后一次的那一下
        List<Triggers.Bound> two = List.of(bound("other", "{\"type\":\"trigger\","
                + " \"on\":\"damage_taken\",\"actions\":[{\"force_parry\":{\"seconds\":9}}]}"));
        fire(Triggers.DAMAGE_TAKEN, Facts.NONE, two, counters, Map.of(), null, 4.0);
        assertTrue(Triggers.forcedPrecise(HOLDER, two, counters, 12.5), "第二张卡自己那 9 秒");
        assertEquals(1.0, Triggers.inFlight(counters, HOLDER, 12.5),
                "强制精准的在途与冷却同在引擎命名空间里，但各是一张资源（0.1 那张已过期）");
    }

    /** 引擎命名空间是预留的：内容用 {@code trigger.} 开叠层会把冷却与资源记在同一本账上。 */
    @Test
    public void theEngineNamespaceGateFailsOnTheRealLoadPath() {
        assertTrue(Triggers.isReservedStackId(Triggers.COOLDOWN_PREFIX + "last_stand"));
        assertTrue(!Triggers.isReservedStackId("barrier"));

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

    /** 致命口径只有一处：血量减这一发 ≤ 0（原版算完护甲之后的数）。 */
    @Test
    public void fatalMeansThisHitWouldFinishMe() {
        assertTrue(Triggers.isFatal(10.0, 10.0), "正好打死也算致命");
        assertTrue(Triggers.isFatal(10.0, 10.5));
        assertTrue(!Triggers.isFatal(10.0, 9.9));
    }

    /** 独立伤害只按<b>基数 × 系数</b>算：它不是把刚才那一发再乘一遍，所以基数与乘区无关。 */
    @Test
    public void extraDamageIsPlannedFromBasesNotFromTheSettledNumber() {
        CounterStore counters = new CounterStore();
        List<Triggers.Bound> bound = List.of(bound("shockwave", "{\"type\":\"trigger\",\"on\":\"hit\","
                + " \"actions\":[{\"damage\":{\"basis\":\"armor\",\"coefficient\":0.5,\"radius\":3.5}},"
                + " {\"damage\":{\"basis\":\"const\",\"coefficient\":2.0}},"
                + " {\"knockback\":{\"strength\":1.2,\"radius\":2}}]}"));
        Triggers.Result result = fire(Triggers.HIT, Facts.NONE, bound, counters, Map.of(), null,
                new Triggers.Bases(20.0, 7.0, 99.0), 0.0);

        assertEquals(2, result.extraHits().size(), result.extraHits().toString());
        assertEquals(10.0, result.extraHits().get(0).amount(), 1e-9, "护甲 20 × 0.5");
        assertEquals(3.5, result.extraHits().get(0).radius(), 1e-9, "半径要交出去，找目标在世界侧做");
        assertTrue(result.extraHits().get(0).attribution().contains("护甲值"),
                result.extraHits().get(0).attribution());
        assertEquals(2.0, result.extraHits().get(1).amount(), 1e-9, "const 就是系数本身，不乘 incoming");
        assertEquals(1, result.knockbacks().size());
        assertEquals(1.2, result.knockbacks().get(0).strength(), 1e-9);
    }

    /** 基数算出 0、或半径要随叠层涨：都报出来，不静默按 0 打一发。 */
    @Test
    public void aZeroBasisOrAnUndeclaredRadiusSourceIsReported() {
        CounterStore counters = new CounterStore();
        List<Triggers.Bound> noArmor = List.of(bound("shockwave", "{\"type\":\"trigger\",\"on\":\"hit\","
                + " \"actions\":[{\"damage\":{\"basis\":\"armor\",\"coefficient\":0.5}}]}"));
        Triggers.Result bare = fire(Triggers.HIT, Facts.NONE, noArmor, counters, Map.of(), null,
                Triggers.Bases.NONE, 0.0);
        assertTrue(bare.extraHits().isEmpty());
        assertTrue(bare.unsupported().toString().contains("= 0"),
                "0 伤害要说明是基数为 0，不是没生效: " + bare.unsupported());

        List<Triggers.Bound> perStack = List.of(bound("growth", "{\"type\":\"trigger\",\"on\":\"hit\","
                + " \"actions\":[{\"damage\":{\"basis\":\"attack\",\"coefficient\":1.0,"
                + " \"radius_per_stack\":0.5}}]}"));
        Triggers.Result grown = fire(Triggers.HIT, Facts.NONE, perStack, counters, Map.of(), null,
                new Triggers.Bases(0.0, 5.0, 0.0), 0.0);
        assertTrue(grown.extraHits().isEmpty(), "半径随哪条叠层涨还没定，不能猜");
        assertTrue(grown.unsupported().toString().contains("radius_per_stack"), grown.unsupported().toString());
    }

    // —— 夹具 ——

    private static StackClause stack(String id, double cap, double duration) {
        List<String> errors = new ArrayList<>();
        StackClause clause = (StackClause) StackClause.parse(
                json("{\"type\":\"stacks\",\"id\":\"" + id + "\",\"cap\":" + cap + ",\"duration\":"
                        + duration + "}"), errors);
        assertTrue(errors.isEmpty() && clause != null, errors.toString());
        return clause;
    }

    private static CardIndex indexWith(String fileName, String body) {
        var tags = ContentReader.readTags(Map.of("nextcard:card_tags/attack.json",
                json("{\"name\": \"tag.nextcard.attack\"}")));
        assertTrue(tags.ok(), tags.errors().toString());
        LoadResult<CardIndex> cards = ContentReader.readCards(
                Map.of("nextcard:cards/" + fileName, json(body)), tags.value());
        assertTrue(cards.ok(), cards.errors().toString());
        return cards.value();
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
