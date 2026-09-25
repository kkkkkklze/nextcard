package com.klze.nextcard.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.klze.nextcard.core.card.CardDefinition;
import com.klze.nextcard.core.draw.CardClass;
import com.klze.nextcard.core.effect.Action;
import com.klze.nextcard.core.effect.ChargeTable;
import com.klze.nextcard.core.effect.EffectClause;
import com.klze.nextcard.core.effect.EffectClauses;
import com.klze.nextcard.core.effect.MechanicClause;
import com.klze.nextcard.core.effect.MechanicProfile;
import com.klze.nextcard.core.effect.Mechanics;
import com.klze.nextcard.core.effect.ModifierClause;
import com.klze.nextcard.core.effect.StackClause;
import com.klze.nextcard.core.effect.TriggerClause;
import com.klze.nextcard.core.effect.WindowMath;
import com.klze.nextcard.core.load.ContentReader;
import com.klze.nextcard.core.load.LoadResult;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 机制词表门：盾反与蓄力两个三级池需要的语汇（层数/资源、事件触发与窗口、机制声明、
 * 槽位改写与数值通道）必须能把卡表的真实数值算出来，且写坏的内容必须报错。
 *
 * <p>本套件只跑纯逻辑，不启游戏。所有数字取自卡表
 * （判定窗口延长 30%/50%、难度 ÷3、蓄力条上限 3+2 秒、蓄力点 0.5/2/2/3/5 秒、
 * 每层「战意」+3% 蓄力速度……），用来证明"这些卡面写法在语汇里成立"。</p>
 */
public class ParryChargeVocabularyTest {

    /** 槽位声明合成方式：延长项走加法、乘区走乘法、难度走除法——卡面改不了这件事。 */
    @Test
    public void windowFollowsSlotDeclaredCombining() {
        List<ModifierClause> modifiers = new ArrayList<>();
        modifiers.add(modifier("window.length", 0.30));   // 时机感知：窗口延长 30%
        modifiers.add(modifier("window.length", 0.50));   // 节奏记忆：窗口延长 50%
        modifiers.add(modifier("window.scale", 2.0));     // 绝境读秒：窗口延长一倍
        modifiers.add(modifier("window.difficulty", 3.0)); // 反射神经：窗口缩小到 1/3

        double base = 0.4;
        double window = WindowMath.windowFrom(base, modifiers, counter -> 0);
        assertEquals(0.4 * 1.8 * 2.0 / 3.0, window, 1e-9, "加法延长 × 乘区 ÷ 难度");

        // 两个难度项相乘（忘我之境：第 3 次减半、连续 5 次再减半）
        modifiers.add(modifier("window.difficulty", 2.0));
        modifiers.add(modifier("window.difficulty", 2.0));
        assertEquals(0.4 * 1.8 * 2.0 / 12.0, WindowMath.windowFrom(base, modifiers, counter -> 0), 1e-9);

        // 乘区也是独立的槽位：同一件事写成"延长项"就只能加算
        List<ModifierClause> additiveOnly = List.of(
                modifier("window.length", 0.30), modifier("window.length", 0.50));
        assertEquals(0.4 * 1.8, WindowMath.windowFrom(base, additiveOnly, counter -> 0), 1e-9,
                "两个延长项必须加算，卡面写不出'乘法叠加'");

        // 窗口不为 0：难度无限叠加时保留下限
        assertTrue(WindowMath.window(0.4, 0.0, 1.0, 1000.0) > 0.0);
    }

    /** 蓄力条：上限取最高、加成加算；没有招式卡时是"意思一下"的 0.5 秒且无招式。 */
    @Test
    public void chargeBarTakesHighestThenAddsBonus() {
        List<MechanicClause> none = List.of();
        ChargeTable bare = ChargeTable.of(none, List.of(), List.of(), counter -> 0);
        assertFalse(bare.granted(), "没有任何招式卡 = 未取得蓄力能力");
        assertEquals(ChargeTable.DEFAULT_BAR, bare.barMax());
        assertTrue(bare.at(0.5).isEmpty(), "没有招式卡就没有蓄力点");

        // 挑击 0.5 / 过肩劈 2 / 横扫千军 2 / 蓄力架势 3（同名招式卡各自给出一段上限，取最高）
        List<MechanicClause> declarations = List.of(
                charge(0.5), charge(2.0), charge(2.0), charge(3.0));
        // 开山：自带蓄力条上限 +2 秒
        List<ModifierClause> modifiers = List.of(modifier("charge.bar_bonus", 2.0));
        ChargeTable owned = ChargeTable.of(declarations, List.of(), modifiers, counter -> 0);
        assertTrue(owned.granted());
        assertEquals(5.0, owned.barMax(), 1e-9, "3 秒的最高档 + 2 秒加成 = 5 秒");
    }

    /** 蓄力点：到点覆盖（同一档多张卡一起触发），声明 inherits 的档把下面一档带出来。 */
    @Test
    public void chargePointsOverrideAndInherit() {
        List<TriggerClause> triggers = List.of(
                release(0.5, false, "stun"),
                release(2.0, false, "stun"),
                release(2.0, false, "knockback"),
                release(3.0, true, "damage"),
                release(5.0, false, "ignore_armor"));
        // 开山：上限 +2 秒，所以 5 秒是打满的一档、7 秒才是过量
        ChargeTable table = ChargeTable.of(List.of(charge(3.0)), triggers,
                List.of(modifier("charge.bar_bonus", 2.0)), counter -> 0);

        assertEquals(1, table.at(0.6).size(), "0.5 秒档只触发它自己");
        assertEquals(2, table.at(2.5).size(), "同一个蓄力点上的两张卡一起触发");
        assertTrue(table.at(1.0).get(0).atSeconds() < 1.0, "1 秒时生效的是 0.5 秒档");
        assertEquals(3, table.at(3.5).size(), "3 秒档 inherits，带上 2 秒档的两张");
        assertEquals(1, table.at(5.0).size(), "5 秒档不继承");
        assertTrue(table.at(0.4).isEmpty(), "不足第一个蓄力点：按普通攻击结算，引擎侧无招式");

        assertEquals(0.0, table.overcharge(5.0), 1e-9);
        assertEquals(2.0, table.overcharge(7.0), 1e-9, "超过上限每段转 1 层过量蓄势");
    }

    /** 层数/资源：上限取最宽松、"解除"是一个事实而不是一个大数字，每层映射按通道加算。 */
    @Test
    public void stacksCapTakesWidestAndUncappedIsAFact() {
        List<ModifierClause> modifiers = new ArrayList<>();
        // 持盾耐力：壁障最多 10 层（基准 3 层由声明给出）
        modifiers.add(modifier("stack.barrier.cap", 10.0));
        MechanicProfile profile = MechanicProfile.fold(modifiers, counter -> 0);
        assertEquals(10.0, profile.stackCap("barrier", 3.0), 1e-9);

        // 全向盾墙：壁障层数上限解除
        modifiers.add(uncapped("stack.barrier.cap"));
        MechanicProfile uncapped = MechanicProfile.fold(modifiers, counter -> 0);
        assertTrue(uncapped.uncapped("stack.barrier.cap"));
        assertEquals(StackClause.UNBOUNDED, uncapped.stackCap("barrier", 3.0));
    }

    /** 数值通道：附带数值就是通道改写；原版属性与自定义数值分开。 */
    @Test
    public void channelsFoldAdditivelyAndSplitVanilla() {
        List<ModifierClause> modifiers = List.of(
                modifier("channel.armor", 10.0),
                modifier("channel.armor", 8.0),
                modifier("channel.crit_damage", 0.20),
                modifier("channel.crit_damage", 0.40),
                modifier("channel.max_health_scale", 0.15));
        MechanicProfile profile = MechanicProfile.fold(modifiers, counter -> 0);
        assertEquals(18.0, profile.channel("armor"), 1e-9, "同通道加算");
        assertEquals(0.60, profile.channel("crit_damage"), 1e-9);
        assertEquals(0.15, profile.channel("max_health_scale"), 1e-9,
                "乘区通道照样是增量（0.15 = +15%），自成相乘桶是通道语义、不是另一种合成方式");
        assertTrue(Mechanics.SCALE_CHANNELS.contains("max_health_scale"));
        assertEquals(Set.of("armor"), profile.vanillaChannels().keySet(),
                "只有护甲/生命上限/移速/攻速能直接落成原版属性");
    }

    /** 每层来源：层数由拥有状态提供（每层「战意」+3% 蓄力速度）。 */
    @Test
    public void perLayerSourceScalesWithOwnedStacks() {
        List<ModifierClause> modifiers = List.of(
                perLayer("charge.rate", "war_intent", 0.03),
                modifier("charge.rate", 0.30));  // 站定 +30%
        MechanicProfile three = MechanicProfile.fold(modifiers, counter -> counter.equals("war_intent") ? 3 : 0);
        assertEquals(0.39, three.number("charge.rate", 0.0), 1e-9);

        MechanicProfile none = MechanicProfile.fold(modifiers, counter -> 0);
        assertEquals(0.30, none.number("charge.rate", 0.0), 1e-9,
                "没有层数时每层来源不生效，但无条件来源照常");
    }

    /** 四类子句必须把结构写坏的地方全部拦下：未知类型/动作/条件、未声明的机制与叠层、互相矛盾的值。 */
    @Test
    public void badContentFailsFast() {
        assertEquals(Set.of("stacks", "trigger", "mechanic", "mechanic_modifier"), EffectClauses.types());

        assertError(parse("[{\"type\": \"nope\"}]"), "unknown effect type: nope");
        assertError(parse("[{\"type\": \"trigger\", \"on\": \"not_an_event\","
                + " \"actions\": [{\"stun\": {\"seconds\": 1}}]}]"), "unknown event: not_an_event");
        assertError(parse("[{\"type\": \"trigger\", \"on\": \"parry_success\","
                + " \"actions\": [{\"teleport\": {\"blocks\": 3}}]}]"), "unknown action type: teleport");
        assertError(parse("[{\"type\": \"trigger\", \"on\": \"parry_success\","
                + " \"actions\": [{\"stun\": {}}]}]"), "action stun needs field seconds");
        assertError(parse("[{\"type\": \"trigger\", \"on\": \"parry_success\","
                + " \"actions\": [{\"stun\": {\"seconds\": 1, \"meters\": 2}}]}]"),
                "unknown key meters");
        assertError(parse("[{\"type\": \"trigger\", \"on\": \"block_success\", \"at\": 2.0,"
                + " \"actions\": [{\"stun\": {\"seconds\": 1}}]}]"), "at is only valid on charge_release");
        assertError(parse("[{\"type\": \"mechanic_modifier\", \"target\": \"window.girth\", \"value\": 1}]"),
                "unknown mechanic slot: window.girth");
        assertError(parse("[{\"type\": \"mechanic\", \"id\": \"parry\", \"girth\": 1}]"),
                "unknown parameter girth");
        assertError(parse("[{\"type\": \"stacks\", \"id\": \"Bad Id\", \"cap\": 3}]"), "id must match");
        assertError(parse("[{\"type\": \"stacks\", \"id\": \"barrier\", \"cap\": 3,"
                + " \"gain\": [{\"on\": \"parry_success\", \"at_least\": 2}]}]"), "unknown key at_least");
        assertError(parse("[{\"type\": \"mechanic_modifier\", \"target\": \"window.length\","
                + " \"uncapped\": true}]"), "only cap slots can be unlocked");
        assertError(parse("[{\"type\": \"mechanic_modifier\", \"target\": \"window.length\","
                + " \"value\": 0.3, \"uncapped\": true}]"), "needs exactly one of value / source / uncapped");
    }

    /** 枚举类槽位：格挡角度档取最宽松（正面 → 正面+两侧 → 全向），写数字即报错。 */
    @Test
    public void blockAngleTakesWidestTier() {
        MechanicProfile one = MechanicProfile.fold(
                List.of(ModifierClause.ofText("block.angle", "front_side")), counter -> 0);
        assertEquals("front_side", one.text("block.angle"));

        MechanicProfile both = MechanicProfile.fold(List.of(
                ModifierClause.ofText("block.angle", "all"),
                ModifierClause.ofText("block.angle", "front_side")), counter -> 0);
        assertEquals("all", both.text("block.angle"), "两张卡各写一档，取最宽松");

        assertError(parse("[{\"type\": \"mechanic_modifier\", \"target\": \"block.angle\", \"value\": 2}]"),
                "is an enum slot and needs a string value");
        assertError(parse("[{\"type\": \"mechanic_modifier\", \"target\": \"block.angle\","
                + " \"value\": \"behind\"}]"), "block.angle must be one of");
    }

    /** 跨卡校验：改写没人声明过的机制、引用不存在的叠层、语义唯一槽位出现两个值。 */
    @Test
    public void crossCardReferencesFailFast() {
        List<CardDefinition> orphanMechanic = List.of(card("a", parseOne(
                "{\"type\": \"mechanic_modifier\", \"target\": \"charge.rate\", \"value\": 0.3}")));
        assertError(EffectClauses.validateReferences(orphanMechanic), "no card declares it");

        List<CardDefinition> orphanStack = List.of(card("a", parseOne(
                "{\"type\": \"mechanic_modifier\", \"target\": \"stack.ghost.cap\", \"value\": 3}")));
        assertError(EffectClauses.validateReferences(orphanStack), "undeclared stack ghost");

        List<CardDefinition> orphanAction = List.of(
                card("a", parseOne("{\"type\": \"mechanic\", \"id\": \"parry\", \"base_window\": 0.4}")),
                card("b", parseOne("{\"type\": \"trigger\", \"on\": \"parry_success\","
                        + " \"actions\": [{\"stacks\": {\"id\": \"ghost\", \"amount\": 1}}]}")));
        assertError(EffectClauses.validateReferences(orphanAction), "undeclared stack ghost");

        List<CardDefinition> conflict = List.of(
                card("a", parseOne("{\"type\": \"mechanic\", \"id\": \"charge\", \"bar_max\": 3}")),
                card("b", parseOne("{\"type\": \"mechanic_modifier\","
                        + " \"target\": \"charge.cancelable\", \"value\": 0}")),
                card("c", parseOne("{\"type\": \"mechanic_modifier\","
                        + " \"target\": \"charge.cancelable\", \"value\": 1}")));
        assertError(EffectClauses.validateReferences(conflict), "conflicting values must be resolved");

        // 合法内容：声明了机制与叠层、两个同值 SET 不冲突
        List<CardDefinition> ok = List.of(
                card("a", parseOne("{\"type\": \"mechanic\", \"id\": \"charge\", \"bar_max\": 3}")),
                card("b", parseOne("{\"type\": \"stacks\", \"id\": \"barrier\", \"cap\": 3,"
                        + " \"gain\": [{\"on\": \"block_success\", \"amount\": 1}]}")),
                card("c", parseOne("{\"type\": \"mechanic_modifier\","
                        + " \"target\": \"charge.rate\", \"value\": 0.3}")),
                card("d", parseOne("{\"type\": \"mechanic_modifier\","
                        + " \"target\": \"stack.barrier.cap\", \"value\": 10}")));
        assertTrue(EffectClauses.validateReferences(ok).isEmpty(),
                "合法内容不得报错：" + EffectClauses.validateReferences(ok));
    }

    /** 一个完整的小场景：两张卡（一张给机制与上限，一张改规则）能同时解析并折叠出预期数值。 */
    @Test
    public void shieldParryAndChargeCardsCoexist() {
        List<EffectClause> parryPool = parse("["
                + "{\"type\": \"mechanic\", \"id\": \"parry\", \"base_window\": 0.4,"
                + " \"ordinary_reduction\": 0.2},"
                + "{\"type\": \"mechanic_modifier\", \"target\": \"window.length\", \"value\": 0.3},"
                + "{\"type\": \"trigger\", \"on\": \"parry_success\","
                + " \"window\": {\"seconds\": 3.0, \"uses\": 1},"
                + " \"actions\": [{\"crit\": {\"guaranteed\": true}}]},"
                + "{\"type\": \"stacks\", \"id\": \"barrier\", \"cap\": 3, \"duration\": 6.0,"
                + " \"gain\": [{\"on\": \"block_success\", \"amount\": 1}],"
                + " \"per_stack\": [{\"target\": \"channel.damage_reduction\", \"value\": 0.03}]}]").value();
        assertEquals(4, parryPool.size(), "同一个卡上的多种子句互不干扰");

        List<MechanicClause> mechanics = new ArrayList<>();
        List<ModifierClause> modifiers = new ArrayList<>();
        List<TriggerClause> triggers = new ArrayList<>();
        List<StackClause> stacks = new ArrayList<>();
        for (EffectClause clause : parryPool) {
            if (clause instanceof MechanicClause mechanic) {
                mechanics.add(mechanic);
            } else if (clause instanceof ModifierClause modifier) {
                modifiers.add(modifier);
            } else if (clause instanceof TriggerClause trigger) {
                triggers.add(trigger);
            } else if (clause instanceof StackClause stack) {
                stacks.add(stack);
            }
        }
        assertEquals(1, mechanics.size());
        assertEquals(0.2, mechanics.get(0).param("ordinary_reduction", 0.0), 1e-9);
        assertEquals(1, triggers.size());
        assertEquals(3.0, triggers.get(0).windowSeconds(), 1e-9);
        assertEquals(1, triggers.get(0).uses());
        assertFalse(triggers.get(0).unlimitedUses());
        assertEquals(3.0, stacks.get(0).cap(), 1e-9);
        assertEquals(StackClause.Scope.PERSISTENT, stacks.get(0).scope());
        assertEquals(0.03, stacks.get(0).perStack().get(0).value(), 1e-9);
        assertEquals(0.4 * 1.3, WindowMath.windowFrom(0.4, modifiers, counter -> 0), 1e-9);
    }

    /**
     * 走真实内容读取路径（ContentReader）：标签 → 卡片（四类子句齐上）→ 跨卡校验，
     * 证明注册的语汇不是"只有测试里能解析"，而是能从 JSON 一路加载进注册表。
     */
    @Test
    public void jsonContentLoadsThroughContentReader() {
        Map<String, JsonObject> tagFiles = Map.of(
                "nextcard:card_tags/attack.json", obj("{\"name\": \"tag.nextcard.attack\"}"));
        LoadResult<com.klze.nextcard.core.tag.TagIndex> tags = ContentReader.readTags(tagFiles);
        assertTrue(tags.ok(), "tag errors: " + tags.errors());

        Map<String, JsonObject> cardFiles = Map.of(
                // 招式卡：声明并授予机制（蓄力条上限 3 秒）
                "nextcard:cards/stance.json", obj("{\"tier\": 3, \"card_class\": \"B\","
                        + " \"tags\": [\"nextcard:attack\"], \"effects\": ["
                        + "{\"type\": \"mechanic\", \"id\": \"charge\", \"bar_max\": 3.0},"
                        + "{\"type\": \"mechanic_modifier\", \"target\": \"channel.attack_speed\", \"value\": 0.05}]}"),
                // 辅助卡：只改规则（+2 秒上限、站定加速、蓄力点）
                "nextcard:cards/pile_up.json", obj("{\"tier\": 5, \"card_class\": \"B\","
                        + " \"tags\": [\"nextcard:attack\"], \"effects\": ["
                        + "{\"type\": \"mechanic_modifier\", \"target\": \"charge.bar_bonus\", \"value\": 2.0},"
                        + "{\"type\": \"mechanic_modifier\", \"target\": \"charge.rate\", \"value\": 0.3},"
                        + "{\"type\": \"trigger\", \"on\": \"charge_release\", \"at\": 2.0,"
                        + " \"actions\": [{\"stun\": {\"seconds\": 1.5}}]}]}"));
        LoadResult<com.klze.nextcard.core.card.CardIndex> cards = ContentReader.readCards(cardFiles, tags.value());
        assertTrue(cards.ok(), "card errors: " + cards.errors());

        List<MechanicClause> mechanics = new ArrayList<>();
        List<ModifierClause> modifiers = new ArrayList<>();
        List<TriggerClause> triggers = new ArrayList<>();
        for (var card : cards.value().byId().values()) {
            for (EffectClause clause : card.effects()) {
                if (clause instanceof MechanicClause mechanic) {
                    mechanics.add(mechanic);
                } else if (clause instanceof ModifierClause modifier) {
                    modifiers.add(modifier);
                } else if (clause instanceof TriggerClause trigger) {
                    triggers.add(trigger);
                }
            }
        }
        assertEquals(5, mechanics.size() + modifiers.size() + triggers.size(), "四类子句解析后各归其位");
        ChargeTable table = ChargeTable.of(mechanics, triggers, modifiers, counter -> 0);
        assertEquals(5.0, table.barMax(), 1e-9, "招式卡的 3 秒 + 辅助卡的 +2 秒");
        assertEquals(0.3, MechanicProfile.fold(modifiers, counter -> 0).number("charge.rate", 0.0), 1e-9);
        assertEquals(1, table.at(2.5).size());

        // 写坏的内容在真实路径上同样是硬错误（跨卡：改了一个没人声明的叠层）
        Map<String, JsonObject> broken = Map.of("nextcard:cards/broken.json",
                obj("{\"tier\": 5, \"card_class\": \"B\", \"tags\": [\"nextcard:attack\"],"
                        + " \"effects\": [{\"type\": \"mechanic_modifier\","
                        + " \"target\": \"stack.ghost.cap\", \"value\": 3}]}"));
        LoadResult<com.klze.nextcard.core.card.CardIndex> bad = ContentReader.readCards(broken, tags.value());
        assertFalse(bad.ok(), "跨卡校验必须拦下未声明的叠层");
        assertError(bad.errors(), "undeclared stack ghost");
    }

    // —— 夹具 ——

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static ModifierClause modifier(String target, double value) {
        return new ModifierClause(target, value, "", 0.0, List.of());
    }

    /** 「解除上限」：不是一个大数字，而是一种独立形态（仅上限类槽位可用）。 */
    private static ModifierClause uncapped(String target) {
        return new ModifierClause(target, 0.0, "", true, "", 0.0, List.of());
    }

    private static ModifierClause perLayer(String target, String counter, double perLayer) {
        return new ModifierClause(target, 0.0, counter, perLayer, List.of());
    }

    private static MechanicClause charge(double barMax) {
        return MechanicClause.of("charge", Map.of("bar_max", barMax));
    }

    private static TriggerClause release(double at, boolean inherits, String actionType) {
        JsonObject body = new JsonObject();
        body.addProperty("type", "trigger");
        body.addProperty("on", "charge_release");
        body.addProperty("at", at);
        if (inherits) {
            body.addProperty("inherits", true);
        }
        JsonArray actions = new JsonArray();
        JsonObject action = new JsonObject();
        JsonObject payload = new JsonObject();
        switch (actionType) {
            case "stun" -> payload.addProperty("seconds", 1.5);
            case "knockback" -> payload.addProperty("strength", 1.0);
            case "ignore_armor" -> payload.addProperty("ratio", 0.5);
            default -> payload.addProperty("coefficient", 0.3);
        }
        if (actionType.equals("damage")) {
            action.add("damage", payload);
            payload.addProperty("basis", "armor");
        } else {
            action.add(actionType, payload);
        }
        actions.add(action);
        body.add("actions", actions);
        List<String> errors = new ArrayList<>();
        TriggerClause trigger = TriggerClause.parse(body, errors);
        assertTrue(errors.isEmpty(), "夹具必须合法：" + errors);
        return trigger;
    }

    private static CardDefinition card(String path, EffectClause clause) {
        return new CardDefinition(new ResourceLocation("nextcard", path), 5, CardClass.B,
                Set.of(new ResourceLocation("nextcard", "test_tag")), Optional.empty(), List.of(),
                List.of(clause));
    }

    private static EffectClause parseOne(String json) {
        List<EffectClause> clauses = parse("[" + json + "]").value();
        assertEquals(1, clauses.size(), "单个子句夹具必须解析成功：" + json);
        return clauses.get(0);
    }

    private static LoadResult<List<EffectClause>> parse(String json) {
        return EffectClauses.parseArray(JsonParser.parseString(json).getAsJsonArray());
    }

    private static void assertError(LoadResult<List<EffectClause>> result, String fragment) {
        assertTrue(result.errors().stream().anyMatch(error -> error.contains(fragment)),
                "期望报错包含「" + fragment + "」，实际：" + result.errors());
    }

    private static void assertError(List<String> errors, String fragment) {
        assertTrue(errors.stream().anyMatch(error -> error.contains(fragment)),
                "期望报错包含「" + fragment + "」，实际：" + errors);
    }
}
