package com.klze.nextcard.logic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.klze.nextcard.core.effect.Combinators;
import com.klze.nextcard.core.effect.Condition;
import com.klze.nextcard.core.effect.Facts;
import com.klze.nextcard.core.effect.Predicate;
import com.klze.nextcard.core.effect.Predicates;
import com.klze.nextcard.core.effect.TriggerClause;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组合子断言：卡表里那些"任意一条 / 若则 / 每三次一次 / n 选 k"终于有地方写了。
 *
 * <p>每条都同时验两件事：判定结果对、<b>理由指得对是哪里</b>。理由不对等于门是假的——
 * 守方第①步（免疫否决）只认带归因的那一步。</p>
 */
public class CombinatorTest {

    private static final Facts LOW = Facts.builder().attackerHp(0.2).build();
    private static final Facts HIGH = Facts.builder().attackerHp(0.9).build();

    private static Predicate parse(String json, List<String> errors) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        return Predicates.parse(object, errors, 0);
    }

    private static Predicate ok(String json) {
        List<String> errors = new ArrayList<>();
        Predicate predicate = parse(json, errors);
        assertTrue(errors.isEmpty() && predicate != null, json + " 应能解析: " + errors);
        return predicate;
    }

    /** 任意一条：成立的那扇门的理由要出现在归因里，不成立时要说清三条都差在哪。 */
    @Test
    public void anyOfShortCircuitsButNeverLosesTheDoor() {
        Predicate any = ok("{\"any_of\": [{\"hp_below\": 0.5}, {\"blocking\": true}]}");
        Predicate.Verdict held = any.test(LOW);
        assertTrue(held.holds());
        assertTrue(held.reason().contains("hp_below"), held.reason());

        Predicate.Verdict missed = any.test(HIGH);
        assertFalse(missed.holds());
        assertTrue(missed.reason().contains("hp_below") && missed.reason().contains("正在格挡"),
                "不成立时要把每条都点出来，否则没法回答\"到底差在哪\": " + missed.reason());
    }

    /** 全部成立：失败方点名，成功方不占地方。 */
    @Test
    public void allOfReportsOnlyWhatFailed() {
        Predicate all = ok("{\"all_of\": [{\"hp_below\": 0.5}, {\"fatal\": true}]}");
        Predicate.Verdict missed = all.test(LOW);
        assertFalse(missed.holds(), "这一份事实里 fatal 没写");
        assertTrue(missed.reason().contains("这一发致命"), missed.reason());
        assertFalse(missed.reason().contains("这一发致命 成立"), "成功那条不该混在失败清单里: " + missed.reason());

        Facts both = Facts.builder().attackerHp(0.2).with("fatal").build();
        assertTrue(all.test(both).holds());
    }

    /** 取反不反转归因：理由写的仍是里面那条的实际状态。 */
    @Test
    public void notFlipsTheVerdictAndKeepsTheEvidence() {
        Predicate not = ok("{\"not\": {\"hp_below\": 0.5}}");
        assertFalse(not.test(LOW).holds());
        assertTrue(not.test(HIGH).holds());
        assertTrue(not.test(LOW).reason().contains("hp_below"), not.test(LOW).reason());
    }

    /** 若…则…否则…：走哪条分支要写在理由里（处决"普通敌人斩杀，精英只吃重创"就是这个形状）。 */
    @Test
    public void ifThenElsePicksOneBranchAndSaysWhich() {
        Predicate branch = ok("{\"if\": {\"target_kind\": \"normal\"},"
                + " \"then\": {\"target_hp_below\": 0.36},"
                + " \"else\": {\"count\": {\"on\": \"heavy_wound\", \"at_least\": 0}}}");
        Facts normalLow = Facts.builder().targetKind("normal").targetHp(0.2).build();
        Facts elite = Facts.builder().targetKind("elite").targetHp(0.2).count("heavy_wound", 1).build();
        assertTrue(branch.test(normalLow).holds(), branch.test(normalLow).reason());
        assertTrue(branch.test(normalLow).reason().contains("若"), branch.test(normalLow).reason());
        assertTrue(branch.test(elite).holds(), branch.test(elite).reason());
        assertTrue(branch.test(elite).reason().contains("否则"), branch.test(elite).reason());

        // 没有 else 时，门槛不过就是整体不过——不猜默认值
        Predicate noElse = ok("{\"if\": {\"target_kind\": \"normal\"}, \"then\": {\"hp_below\": 0.5}}");
        assertFalse(noElse.test(Facts.builder().targetKind("elite").attackerHp(0.1).build()).holds());
    }

    /** n 条里成立 k 条。 */
    @Test
    public void kOfNCountsInsteadOfRequiringAll() {
        Predicate twoOfThree = ok("{\"k_of_n\": 2, \"of\": [{\"hp_below\": 0.5},"
                + " {\"light_below\": 7}, {\"blocking\": true}]}");
        Facts darkLowHp = Facts.builder().attackerHp(0.2).light(3).build();
        assertTrue(twoOfThree.test(darkLowHp).holds(), twoOfThree.test(darkLowHp).reason());
        assertTrue(twoOfThree.test(darkLowHp).reason().contains("2/3"), twoOfThree.test(darkLowHp).reason());
        assertFalse(twoOfThree.test(Facts.builder().attackerHp(0.2).build()).holds());
    }

    /**
     * 顺序轮转：这一次只看指针指的那一条，指针本身是调用方持有的持久计数（{@link CounterStore}），
     * 所以轮转仍是纯函数。循环就是同一条取模后的样子。
     */
    @Test
    public void sequenceReadsThePersistentCursorAndWrapsAround() {
        Predicate combo = ok("{\"sequence\": [{\"hp_below\": 0.9}, {\"fatal\": true}, {\"blocking\": true}],"
                + " \"cursor\": \"combo_step\"}");
        Facts fourth = Facts.builder().attackerHp(0.5).count("combo_step", 3).build();
        // 第 4 手 → 指针 3 → 3 % 3 = 0 → 回到第一条
        assertTrue(combo.test(fourth).holds(), combo.test(fourth).reason());
        assertTrue(combo.test(fourth).reason().contains("第 1/3 步"), combo.test(fourth).reason());

        Facts second = Facts.builder().attackerHp(0.5).count("combo_step", 1).build();
        assertFalse(combo.test(second).holds(), "第 2 首要的是致命一击，这一份事实里没写");
        assertTrue(combo.test(second).reason().contains("第 2/3 步"), combo.test(second).reason());
    }

    /** 嵌套是树的本职工作：any_of 里面再放 all_of 与具名条件。 */
    @Test
    public void combinatorsNestToAnyDepthWithinTheCap() {
        Predicate deep = ok("{\"any_of\": [{\"all_of\": [{\"hp_below\": 0.5},"
                + " {\"any_of\": [{\"unseen\": true}, {\"back_sector\": 60}]}]}]}");
        assertTrue(deep.test(Facts.builder().attackerHp(0.2).angleOffFront(175).build()).holds(),
                "残血 + 背后一刀");
        assertFalse(deep.test(Facts.builder().attackerHp(0.2).angleOffFront(30).build()).holds());
    }

    /** 深度上限：超过就报加载错误，不真递归下去（栈溢出比这难查得多）。 */
    @Test
    public void nestingBeyondTheCapIsALoadError() {
        StringBuilder json = new StringBuilder();
        for (int i = 0; i <= Predicates.MAX_DEPTH; i++) {
            json.append("{\"any_of\": [");
        }
        json.append("{\"hp_below\": 0.5}");
        for (int i = 0; i <= Predicates.MAX_DEPTH; i++) {
            json.append("]}");
        }
        List<String> errors = new ArrayList<>();
        Predicate predicate = parse(json.toString(), errors);
        assertTrue(predicate == null && !errors.isEmpty(), "超过 " + Predicates.MAX_DEPTH + " 层必须报错");
        assertTrue(errors.get(0).contains("nested deeper"), errors.toString());
    }

    /** 拼错的组合子一律加载错误：一个节点只能是一个组合子，伴生字段也不能多。 */
    @Test
    public void malformedCombinatorsAreErrorsNotDefaults() {
        assertError("{\"any_of\": [{\"hp_below\": 0.5}], \"all_of\": []}");
        assertError("{\"any_of\": {\"hp_below\": 0.5}}");
        assertError("{\"any_of\": []}");
        assertError("{\"not\": [{\"hp_below\": 0.5}]}");
        assertError("{\"if\": {\"hp_below\": 0.5}, \"then\": {\"fatal\": true}, \"oops\": 1}");
        assertError("{\"if\": {\"hp_below\": 0.5}}");
        assertError("{\"k_of_n\": 5, \"of\": [{\"hp_below\": 0.5}]}");
        assertError("{\"sequence\": [{\"hp_below\": 0.5}]}");
        assertError("{\"any_of\": [\"hp_below\"]}");
    }

    private static void assertError(String json) {
        List<String> errors = new ArrayList<>();
        Predicate predicate = parse(json, errors);
        assertTrue(predicate == null && !errors.isEmpty(), json + " 必须报错，实际 errors=" + errors);
    }

    /** 树里的叶子也要被跨卡校验看到——只查顶层等于给它留了个静默通道。 */
    @Test
    public void undeclaredStacksAreCaughtInsideTheTree() {
        Predicate tree = ok("{\"any_of\": [{\"all_of\": ["
                + "{\"stacks\": {\"id\": \"wall\", \"at_least\": 3}},"
                + "{\"stacks\": {\"id\": \"ghost\", \"at_least\": 1}}]}]}");
        List<String> errors = new ArrayList<>();
        Predicates.validateTree(tree, Set.of("wall"), "nextcard:test", errors);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("ghost"), "要点名那个没声明的叠层: " + errors);
    }

    /** 走真子句：trigger 的 when 数组里混着叶子与组合子，解析后要还是那棵树。 */
    @Test
    public void aTriggerClauseKeepsTheTreeItWasGiven() {
        List<String> errors = new ArrayList<>();
        JsonObject body = JsonParser.parseString("{\"type\": \"trigger\", \"on\": \"attack\","
                + " \"when\": [{\"unseen\": true},"
                + " {\"any_of\": [{\"hp_below\": 0.3}, {\"stacks\": {\"id\": \"wall\", \"at_least\": 2}}]}],"
                + " \"actions\": [{\"stacks\": {\"id\": \"wall\", \"amount\": 1}}]}")
                .getAsJsonObject();
        TriggerClause clause = (TriggerClause) TriggerClause.parse(body, errors);
        assertTrue(errors.isEmpty(), errors.toString());
        assertNotNull(clause);
        assertEquals(2, clause.when().size(), "两条顶层条件都要在");
        assertEquals("任意一条（3 选 1）", clause.when().get(0).describe(),
                "「未察觉」要展开成三条门的树");
        Combinators.Any second = (Combinators.Any) clause.when().get(1);
        assertEquals(2, second.children().size());
        assertTrue(second.children().get(1) instanceof Condition, "叶子留在原来的位置上，没被压平或改写");
    }

    /** 叶子条件自己也能被当成判定用：Condition 就是树的一种，不是另一套东西。 */
    @Test
    public void leavesArePredicates() {
        Predicate leaf = new Condition("hp_below", 0.5, "", "", "");
        assertTrue(leaf.test(LOW).holds());
        assertFalse(leaf instanceof Combinators.Any);
        assertEquals("hp_below 0.5", leaf.describe());
    }

    /** 空 when 数组＝无条件成立（不是"永远不触发"）。 */
    @Test
    public void anEmptyWhenListMeansUnconditional() {
        assertTrue(Predicates.allHold(List.of(), HIGH).holds());
        assertFalse(Predicates.allHold(List.of(new Condition("fatal", 1.0, "", "", "")), HIGH).holds());
    }
}
