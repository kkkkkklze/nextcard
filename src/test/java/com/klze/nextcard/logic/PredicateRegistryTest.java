package com.klze.nextcard.logic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.klze.nextcard.core.effect.Combinators;
import com.klze.nextcard.core.effect.Condition;
import com.klze.nextcard.core.effect.Facts;
import com.klze.nextcard.core.effect.Predicate;
import com.klze.nextcard.core.effect.Predicates;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判定条件注册表：每个叶子都要能<em>真的判</em>，而且注册表长出一个键、测试没跟上时必须红。
 *
 * <p>"未察觉"三条各验一次，是因为它在卡表里被当成<em>一个</em>词用：展开写错一次，
 * 全表几百张卡的门槛就一起错。</p>
 */
public class PredicateRegistryTest {

    /** 一条叶子的正反两例（同一份事实没法让 hp_below 与 hp_above 同时成立，所以各给一份）。 */
    private record Case(String json, Facts holds, Facts fails) {
    }

    /** 贴着背后的一刀：残血、在动、蓄着力、目标是被控制的精英、墙 3 层、弹反过 2 次。 */
    private static final Facts STAB = Facts.builder()
            .attackerHp(0.2).targetHp(0.1).angleOffFront(175.0).distance(2).light(3).noise(0.1)
            .stillSeconds(6).chargeSeconds(2).targetKind("elite")
            .with("moving").with("blocking").with("charging").with("parried").with("fatal")
            .with("appeared_from_outside_view").with("target_controlled")
            .layers("wall", 3).count("parry_success", 2).build();

    /** 站着不动的那一份（still_seconds 要求"没在动"，与 STAB 是两种现场）。 */
    private static final Facts STILL = Facts.builder()
            .attackerHp(0.2).targetHp(0.1).angleOffFront(175.0).distance(2).light(3).noise(0.1)
            .stillSeconds(6).chargeSeconds(2).targetKind("elite")
            .layers("wall", 3).count("parry_success", 2).build();

    /** 正面、满血、亮处、大步走路——几乎所有门槛都不该过。 */
    private static final Facts OPEN = Facts.builder()
            .attackerHp(0.9).targetHp(0.9).angleOffFront(10.0).distance(9).light(15).noise(1.0)
            .stillSeconds(0).chargeSeconds(0).targetKind("normal").build();

    private static List<Case> cases() {
        List<Case> out = new ArrayList<>();
        out.add(new Case("{\"hp_below\": 0.5}", STAB, OPEN));
        out.add(new Case("{\"hp_above\": 0.5}", OPEN, STAB));
        out.add(new Case("{\"target_hp_below\": 0.5}", STAB, OPEN));
        out.add(new Case("{\"still_seconds\": 5}", STILL, OPEN));
        out.add(new Case("{\"charge_seconds\": 1}", STAB, OPEN));
        out.add(new Case("{\"distance_within\": 8}", STAB, OPEN));
        out.add(new Case("{\"light_below\": 7}", STAB, OPEN));
        out.add(new Case("{\"noise_below\": 0.5}", STAB, OPEN));
        out.add(new Case("{\"back_sector\": 60}", STAB, OPEN));
        out.add(new Case("{\"angle\": \"front\"}", OPEN, STAB));
        out.add(new Case("{\"target_state\": \"controlled\"}", STAB, OPEN));
        out.add(new Case("{\"target_kind\": \"elite\"}", STAB, OPEN));
        out.add(new Case("{\"moving\": true}", STAB, STILL));
        out.add(new Case("{\"blocking\": true}", STAB, OPEN));
        out.add(new Case("{\"charge_active\": true}", STAB, OPEN));
        out.add(new Case("{\"parry\": true}", STAB, OPEN));
        out.add(new Case("{\"fatal\": true}", STAB, OPEN));
        out.add(new Case("{\"appeared_outside_view\": true}", STAB, OPEN));
        out.add(new Case("{\"stacks\": {\"id\": \"wall\", \"at_least\": 3}}", STAB, OPEN));
        out.add(new Case("{\"count\": {\"on\": \"parry_success\", \"at_least\": 2}}", STAB, OPEN));
        return out;
    }

    private static Predicate parse(String json, List<String> errors) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        return Predicates.parse(object, errors, 0);
    }

    /** 注册表 ↔ 测试用例必须一一对应：多一个键没人验＝它其实没被实现。 */
    @Test
    public void theRegistryAndTheCasesCoverExactlyTheSameKeys() {
        TreeSet<String> tested = new TreeSet<>();
        for (Case testCase : cases()) {
            tested.add(firstKey(testCase.json()));
        }
        assertEquals(new TreeSet<>(Condition.KEYS), tested,
                "叶子键表与用例表不一致（多出来的键=没验过，少掉的键=用例过期）");
    }

    private static String firstKey(String json) {
        return new ArrayList<>(JsonParser.parseString(json).getAsJsonObject().keySet()).get(0);
    }

    /** 每条叶子都要有一次成立、一次不成立，并且两次的理由都非空。 */
    @Test
    public void everyLeafHoldsOnceFailsOnceAndAlwaysExplainsItself() {
        for (Case testCase : cases()) {
            List<String> errors = new ArrayList<>();
            Predicate predicate = parse(testCase.json(), errors);
            assertTrue(errors.isEmpty(), testCase.json() + " 应该能解析: " + errors);
            assertNotNull(predicate, testCase.json());

            Predicate.Verdict held = predicate.test(testCase.holds());
            Predicate.Verdict missed = predicate.test(testCase.fails());
            assertTrue(held.holds(), testCase.json() + " 该成立: " + held.reason());
            assertFalse(missed.holds(), testCase.json() + " 该不成立: " + missed.reason());
            assertFalse(held.reason().isEmpty(), "成立也要有归因: " + testCase.json());
            assertFalse(missed.reason().isEmpty(), "不成立也要说清差在哪: " + testCase.json());
        }
    }

    /**
     * 账本是怎么<em>进到</em>快照里的：{@link Facts#withLedger} 是执行器唯一的贴法。
     *
     * <p>条件叶子早就验过 {@code stacks} 与 {@code count}（上面那张正反例表），但"叶子会判"与
     * "账本里有数"是两件事——这一条钉的是贴的动作本身：读得到、同名以当下为准、贴的时候不改掉
     * 别的事实、两本账（层数与次数）各贴各的互不覆盖。世界里那条出处（真的是
     * {@code CounterStore} 的账）由 GameTest {@code stackGatedConditionsReadTheLiveLayerCount}
     * 与 {@code countedEventsReachConditionsInTheWorld} 钉住。</p>
     */
    @Test
    public void ledgerSnapshotReachesTheReferenceConditions() {
        Predicate gate = parse("{\"stacks\": {\"id\": \"venom\", \"at_least\": 3}}", new ArrayList<>());
        Facts bare = Facts.builder().targetKind("normal").build();
        assertFalse(gate.test(bare).holds(), "没贴账之前一层都不该有");

        Facts three = bare.withLedger(java.util.Map.of("venom", 3, "wall", 1), java.util.Map.of());
        Predicate.Verdict verdict = gate.test(three);
        assertTrue(verdict.holds(), "贴进来就该读得到: " + verdict.reason());
        assertEquals(1, three.layers("wall"), 1e-9, "一次贴多条都要在");
        assertEquals("normal", three.targetKind(), "贴账不能顺手改掉别的事实");
        assertEquals(5, three.withLedger(java.util.Map.of("venom", 5), java.util.Map.of()).layers("venom"),
                1e-9, "同名的以当下这份为准（层数会涨会掉）");

        Predicate counted = parse("{\"count\": {\"on\": \"parry_success\", \"at_least\": 2}}",
                new ArrayList<>());
        assertFalse(counted.test(three).holds(), "贴了层数不等于贴了次数——两本账分开");
        assertTrue(counted.test(three.withLedger(java.util.Map.of(), java.util.Map.of("parry_success", 2)))
                .holds(), "次数贴进来就该读得到");
        assertEquals(3, three.withLedger(java.util.Map.of(), java.util.Map.of("parry_success", 1))
                .layers("venom"), 1e-9, "贴次数不能改掉层数那本账");
    }

    /** 角度档由《00》背后 120° 的定义对称推出来：正面 ±60、正面+两侧 ±120、全向不限。 */
    @Test
    public void angleLadderFollowsTheBackSectorDefinition() {
        assertEquals(Predicates.BACK_SECTOR_HALF_ANGLE, Condition.FRONT_HALF_ANGLE, 1e-9,
                "背后 60° 与正面 60° 是同一条定义的两半");
        assertTrue(holds("{\"angle\": \"front_side\"}", Facts.builder().angleOffFront(115).build()),
                "正面+两侧含 115°");
        assertFalse(holds("{\"angle\": \"front_side\"}", Facts.builder().angleOffFront(125).build()),
                "125° 已经进背后档");
        assertTrue(holds("{\"angle\": \"all\"}", Facts.builder().angleOffFront(180).build()), "全向永不拦");
        assertTrue(holds("{\"back_sector\": 60}", Facts.builder().angleOffFront(179).build()), "离背面中线 1°");
        assertFalse(holds("{\"back_sector\": 60}", Facts.builder().angleOffFront(115).build()),
                "离背面中线 65° 不算背后");
    }

    private static boolean holds(String json, Facts facts) {
        List<String> errors = new ArrayList<>();
        Predicate predicate = parse(json, errors);
        assertTrue(errors.isEmpty(), errors.toString());
        return predicate.test(facts).holds();
    }

    /**
     * 「未察觉」＝满足任意一条即可（《00》L1016 的三条，一条不少、一条不多）。
     * 这个具名展开是全卡表共用的，写错一次就有几百张卡的门槛一起错。
     */
    @Test
    public void unseenIsExactlyThoseThreeDoors() {
        assertTrue(holds("{\"unseen\": true}", Facts.builder().angleOffFront(170).build()), "①背后 120° 内");
        assertTrue(holds("{\"unseen\": true}", Facts.builder().with("target_blind").build()), "②失明");
        assertTrue(holds("{\"unseen\": true}", Facts.builder().with("target_attracted_elsewhere").build()),
                "②被别的单位吸引");
        assertTrue(holds("{\"unseen\": true}", Facts.builder().with("appeared_from_outside_view").build()),
                "③刚从视野外出现");
        assertFalse(holds("{\"unseen\": true}", Facts.NONE), "正面站着、目标清醒、一直在它视野里");

        assertTrue(holds("{\"unseen\": false}", Facts.NONE), "写 false 就是要求它不成立");
        assertFalse(holds("{\"unseen\": false}", Facts.builder().angleOffFront(179).build()));
    }

    /** 具名条件展开的那棵树，理由要指到具体哪一条门放行——免疫否决要引用它。 */
    @Test
    public void theNamedCompositeNamesWhichDoorLetItThrough() {
        Predicate.Verdict verdict = Predicates.unseen().test(
                Facts.builder().angleOffFront(30).with("target_blind").build());
        assertTrue(verdict.holds());
        assertTrue(verdict.reason().contains("失明"), "归因要说到那条门: " + verdict.reason());
    }

    /** 写歪的条件必须是加载错误，不能"未知即成立"，也不能静默丢掉。 */
    @Test
    public void malformedConditionsAreErrorsNotDefaults() {
        assertError("{\"hp_below\": \"much\"}");
        assertError("{\"not_a_condition\": 1}");
        assertError("{\"hp_below\": 0.5, \"hp_above\": 0.9}");
        assertError("{\"angle\": \"south\"}");
        assertError("{\"target_kind\": \" miniboss\"}");
        assertError("{\"unseen\": 3}");
        assertError("{\"stacks\": {\"id\": \"wall\"}}");
        // 计数对象必须是已注册事件：写错一个事件名的计数条件永远不会成立，表现与"这卡没用"一样，
        // 只能靠加载期拦住（2026-09-28 起）。
        assertError("{\"count\": {\"on\": \"heavy_wound\", \"at_least\": 1}}");
        assertError("{\"count\": {\"on\": \"channel.armor\", \"at_least\": 1}}");
    }

    private static void assertError(String json) {
        List<String> errors = new ArrayList<>();
        Predicate predicate = parse(json, errors);
        assertTrue(predicate == null && !errors.isEmpty(), json + " 必须报错，实际 errors=" + errors);
    }

    /** 事实快照自己也拒绝拼错的名字：开关写错不能变成静默的"没发生"。 */
    @Test
    public void factsRejectUnknownSwitchesAndKinds() {
        assertThrows(IllegalArgumentException.class,
                () -> Facts.builder().with("target_blinded").build());
        assertThrows(IllegalArgumentException.class,
                () -> Facts.builder().targetKind("miniboss").build());
    }

    /** 对外印出来的词表必须就是解析器认的词表（文档与校验共用同一份，不各写一遍）。 */
    @Test
    public void theVocabularyItPrintsIsTheVocabularyItParses() {
        TreeSet<String> printed = new TreeSet<>(Predicates.vocabulary());
        TreeSet<String> parts = new TreeSet<>(Condition.KEYS);
        parts.addAll(Predicates.namedNames());
        parts.addAll(Combinators.KEYS);
        assertEquals(parts, printed, "词表少了东西");
        for (String combinator : Combinators.KEYS) {
            assertFalse(Condition.KEYS.contains(combinator), "组合子键与叶子键撞了: " + combinator);
        }
    }
}
