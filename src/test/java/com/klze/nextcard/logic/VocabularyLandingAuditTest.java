package com.klze.nextcard.logic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.klze.nextcard.core.effect.Action;
import com.klze.nextcard.core.effect.CounterStore;
import com.klze.nextcard.core.effect.Condition;
import com.klze.nextcard.core.effect.Facts;
import com.klze.nextcard.core.effect.MechanicProfile;
import com.klze.nextcard.core.effect.Mechanics;
import com.klze.nextcard.core.effect.StackClause;
import com.klze.nextcard.core.effect.TriggerClause;
import com.klze.nextcard.core.effect.Triggers;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 词表落点账（补二十四）：每一个注册过的通道与动作，要么<em>真的被消费</em>，要么<em>写进缺口名单</em>。
 *
 * <p>这张账盯的是本项目反复踩到的那一类错——词表先加了名，执行器没人补，于是"卡表那一列是空话"，
 * 而表现方式恰好是"这张卡没用"（不报错、不红）。以前这条清单只写在 {@code docs/机制词表-已注册.md} 里，
 * 靠人记得更新；今天它由代码推导：</p>
 *
 * <ul>
 *   <li><b>通道</b>：有落点 = 要么在 {@link Mechanics#VANILLA_CHANNELS} 里（由 {@code CardAttributes}
 *       投影成原版属性），要么引擎某处写着 {@code channel("<这个名字>")}。两边都查源码，
 *       所以<em>读法里的拼写错误</em>也会被抓到（读一个没注册的通道永远返回 0，是静默的）。</li>
 *   <li><b>动作</b>：有落点 = 把 {@link Action#types()} 里每一个动作喂进 {@link Triggers#fire} 跑一遍，
 *       看它到底<em>产出了一样东西</em>（fired / extraHits / knockbacks / debuffs），还是报了
 *       "引擎还没有执行器"。这里不查源码，查的是行为——所以"case 里什么都不做"这种空转执行器会红。</li>
 * </ul>
 *
 * <p>两个方向的断言都是<em>等式</em>而不是"至少"：名单要变，必须有人改这里，改的时候就会想起同步文档。</p>
 */
public class VocabularyLandingAuditTest {

    /** 已知缺口：它们要的是"伤害分类"这个原版没有的维度，不是再写一次乘法。 */
    private static final Set<String> DECLARED_CHANNEL_GAPS = Set.of("physical_damage", "resistance");

    /** 已知缺口：动作词表里注册了、但还没有执行器的六个（写上去会报"没有执行器"，不静默）。 */
    private static final Set<String> DECLARED_ACTION_GAPS = Set.of(
            "stun", "launch", "extra_resolve", "copy_attack", "ignore_armor", "interrupt");

    /**
     * 已知缺口：判据读得到、但<em>世界侧没人填</em>的开关——写了这类条件的卡今天恒不成立。
     *
     * <p>这一本账盯的正是本项目真踩过两次的形状（{@code stacks} 条件没有出处、{@code still_seconds}
     * 由没人算），那时都是"卡写得对、引擎读得对、中间那一格永远是默认值"。名单里每一项都有原因：
     * {@code charging}＝蓄力运行时还没接（等输入通道口径）；{@code parried}＝弹反成功目前只发
     * {@code parry_success} 事件，条件那一路还没人贴旗子；{@code appeared_from_outside_view} 与
     * {@code target_*} 三条（未察觉那一族）需要世界侧先定"视野/被吸引/失明"到底从哪读。</p>
     */
    private static final Set<String> DECLARED_FLAG_GAPS = Set.of(
            "charging", "parried", "appeared_from_outside_view",
            "target_controlled", "target_attracted_elsewhere", "target_blind");

    /** 已知缺口：{@code Facts.Builder} 上有 setter、但整条 main 里没人调用（该字段恒为默认值）。 */
    private static final Set<String> DECLARED_FIELD_GAPS = Set.of("noise", "chargeSeconds");

    /**
     * 已知缺口：注册了事件名、但世界侧没有派发点会叫醒它（{@code on: "<这个名字>"} 的卡永不响）。
     *
     * <p>这七条各有出处：{@code parry_fail} 等的是"弹反失败要不要给收益"的口径；
     * {@code charge_release} / {@code charge_interrupt} / {@code control_immune} 等的是蓄力与控制的
     * <em>输入通道</em>；{@code shield_down} 等的是破盾判定；{@code stand_still} 等的是"站定多久才算"
     * 那句阈值；{@code skill_cast} 等的是技能栏（powerlevel 那侧有 20 张卡压着）。
     * 名字<em>先注册</em>是允许的（词表先冻结、执行器后补），但"注册了却没人派"这件事
     * 从前只写在文档里，现在由门算。</p>
     */
    private static final Set<String> DECLARED_UNDISPATCHED_EVENTS = Set.of(
            "parry_fail", "charge_release", "charge_interrupt", "control_immune",
            "shield_down", "stand_still", "skill_cast");

    /** {@code facts.flag("target_" + text)} 这一族：读法写在 {@code Condition} 里，取值由 TARGET_STATES 拼。 */
    private static final String TARGET_FLAG_PREFIX = "flag(\"target_\"";

    /** 执行器报"没有执行器"的原话片段（改这句话要连这里一起改，别让它悄悄换词）。 */
    private static final String NO_EXECUTOR = "引擎还没有执行器";

    private static final Pattern CHANNEL_READ = Pattern.compile("channel\\(\"([a-z_]+)\"\\)");

    // —— 账一：通道 ——

    @Test
    public void everyChannelIsEitherReadByTheEngineOrProjectedToAnAttribute() throws IOException {
        Set<String> read = channelReads().keySet();
        assertTrue(!Mechanics.VANILLA_CHANNELS.isEmpty(), "投影清单为空 = CardAttributes 那条路也断了");
        assertTrue(read.size() >= 9,
                "源码扫描只找到 " + read.size() + " 处通道读法，少于已知的 9 条——扫描本身失效了，"
                        + "这道门会空转（先确认 nextcard.main.src 指向 src/main/java/com/klze/nextcard）");

        Set<String> unlanded = new LinkedHashSet<>();
        for (String channel : Mechanics.CHANNELS) {
            if (Mechanics.VANILLA_CHANNELS.contains(channel) || read.contains(channel)) {
                continue;
            }
            unlanded.add(channel);
        }
        assertEquals(DECLARED_CHANNEL_GAPS, unlanded,
                "通道落点账对不上了。多出来的＝注册了词表名但没人读（卡写上去静默无效）；"
                        + "少掉的＝已经有落点，请把这个名字从缺口名单和 docs/机制词表-已注册.md 里删掉。"
                        + " 实际无落点：" + unlanded);
    }

    /**
     * 反方向：引擎里每一处 {@code channel("x")} 都必须点名一个注册过的通道。
     *
     * <p>{@link MechanicProfile#channel} 读不到就回 0.0，所以一个拼错的读法不会报错——它只会让
     * 那条乘区静默不生效，而且<em>卡表那边写对了也没用</em>（加载校验只认注册名，那个值根本进不来）。
     * 这是本门唯一一处"查引擎自己"的断言：内容写错有加载门管，引擎写错只有这里管。</p>
     */
    @Test
    public void everyChannelReadNamesARegisteredChannel() throws IOException {
        Map<String, List<String>> reads = channelReads();
        Set<String> unknown = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : reads.entrySet()) {
            if (!Mechanics.CHANNELS.contains(entry.getKey())) {
                unknown.add(entry.getKey() + " ← " + entry.getValue());
            }
        }
        assertTrue(unknown.isEmpty(),
                "引擎在读一个词表里没有的通道名（读不到恒为 0，静默）：" + unknown);
    }

    /** 通道名 → 读到它的那些文件（注释里的提及不算读法：注释不会让那条通道生效）。 */
    private static Map<String, List<String>> channelReads() throws IOException {
        Map<String, List<String>> reads = new LinkedHashMap<>();
        Path root = mainSrc();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> sources = walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
            for (Path source : sources) {
                for (String line : Files.readAllLines(source)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                        continue;
                    }
                    Matcher matcher = CHANNEL_READ.matcher(line);
                    while (matcher.find()) {
                        reads.computeIfAbsent(matcher.group(1), k -> new ArrayList<>())
                                .add(source.getFileName() + ":" + line.trim());
                    }
                }
            }
        }
        return reads;
    }

    // —— 账二：动作 ——

    @Test
    public void everyActionEitherExecutesOrIsDeclaredAsAGap() {
        Set<String> gaps = new LinkedHashSet<>();
        Map<String, String> hollow = new LinkedHashMap<>();
        for (String type : Action.types()) {
            Triggers.Result result = fireAction(type);
            boolean reported = result.unsupported().stream().anyMatch(s -> s.contains(NO_EXECUTOR));
            boolean produced = !result.fired().isEmpty() || !result.extraHits().isEmpty()
                    || !result.knockbacks().isEmpty() || !result.debuffs().isEmpty();
            if (reported && produced) {
                hollow.put(type, "既报了「" + NO_EXECUTOR + "」又产出了东西（账要二选一）");
            } else if (reported) {
                gaps.add(type);
            } else if (!produced) {
                hollow.put(type, "没报缺口、也没产出任何效果——这就是静默跳过（case 里有分支但什么都不做）");
            }
        }
        assertTrue(hollow.isEmpty(), "有空转的动作执行器：" + hollow);
        assertEquals(DECLARED_ACTION_GAPS, gaps,
                "动作落点账对不上了。多出来的＝词表注册了但既没执行器也没写进缺口名单；"
                        + "少掉的＝已经落地，请把这个名字从缺口名单和 docs 里删掉。 实际无执行器：" + gaps);
    }

    // —— 账三：事实开关（判据读得到 vs 世界侧填得到）——

    /**
     * 每个被读的事实开关都得有人<em>写</em>；每个被写的开关都得有人<em>读</em>；
     * 注册表里不许躺着谁都不碰的开关名。
     *
     * <p>"卡写得对、引擎也读对了，中间那一格永远是默认值"——这是本项目真踩过两次的那类缺陷
     * （{@code stacks} 没出处、{@code still_seconds} 没人算）。开关比通道更隐蔽：通道读错名字
     * 至少还有 {@code channel("…")} 这个形状可扫，开关是<em>两边各写一次字符串</em>，
     * 拼错任何一边都不会有人报错，只会让那条条件安静地恒假。</p>
     */
    @Test
    public void everyFlagTheJudgementReadsIsSomebodyFilledIn() throws IOException {
        Set<String> read = flagReads();
        Set<String> written = flagWrites();
        assertTrue(!read.isEmpty() && !written.isEmpty(),
                "开关扫描空转了（读 " + read.size() + " / 写 " + written.size() + "）——先确认目录属性指对");
        Set<String> unproduced = new LinkedHashSet<>(read);
        unproduced.removeAll(written);
        assertEquals(DECLARED_FLAG_GAPS, unproduced,
                "有开关被判据读了但没人填（写了这类条件的卡恒不成立）。多出来的请先去世界侧补出处，"
                        + "少掉的＝已经填上了，把名字从缺口名单与 docs 里删掉。 实际没人填：" + unproduced);
    }

    /** 另一侧：世界填了但没人读的开关＝白算，而且迟早与判据那边的拼写分家。 */
    @Test
    public void everyFlagTheWorldFillsIsReadByAJudgement() throws IOException {
        Set<String> unread = new LinkedHashSet<>(flagWrites());
        unread.removeAll(flagReads());
        assertTrue(unread.isEmpty(),
                "世界侧算了开关但没有任何判据读它（白算一场，而且迟早与判据那边的拼写分家）：" + unread);
    }

    /** 注册表里不许躺着谁都不碰的开关名——那等于往词表里塞了一个永远用不上的名字。 */
    @Test
    public void everyRegisteredFlagIsTouchedBySomebody() throws IOException {
        Set<String> touched = new LinkedHashSet<>(flagReads());
        touched.addAll(flagWrites());
        Set<String> dead = new LinkedHashSet<>(Facts.FLAG_NAMES);
        dead.removeAll(touched);
        assertTrue(dead.isEmpty(), "注册表里躺着谁都不碰的开关名（要么补判据、要么删掉）：" + dead);
    }

    /** 反方向：两侧写下的开关名必须都在注册表里——{@code flag("mistyped")} 恒为 false，静默。 */
    @Test
    public void everyFlagNameUsedIsRegistered() throws IOException {
        String used = textOf(mainSrc().resolve("core")) + "\n" + textOf(mainSrc().resolve("common"));
        Set<String> names = flagLiterals(used, FLAG_READ);
        names.addAll(flagLiterals(used, FLAG_WRITE));
        Set<String> unknown = new LinkedHashSet<>();
        for (String name : names) {
            if (!Facts.FLAG_NAMES.contains(name)) {
                unknown.add(name);
            }
        }
        assertTrue(unknown.isEmpty(), "用了一个没注册的开关名（读恒 false / 写会在 Facts 构造期抛）：" + unknown);
    }

    // —— 账四：事实字段（Builder 上有 setter vs main 里真有人调用）——

    /**
     * {@code Facts.Builder} 上每个单参数 setter 都要有人<em>带参数</em>调过一次。
     *
     * <p>没出处的那个字段就<em>永远是默认值</em>，于是读它的条件永远同一个答案——和账三同一类病，
     * 只是它藏在位置参数里而不是字符串里。扫描要区分 {@code .noise(0.3)}（填）与 {@code .noise()}
     * （读）：所以匹配的是"点后紧跟一个非右括号的字符"。</p>
     */
    @Test
    public void everyFactFieldHasAProducer() throws IOException {
        String main = textOf(mainSrc());
        Set<String> unproduced = new LinkedHashSet<>();
        for (String setter : builderSetters()) {
            if (!main.contains("." + setter + "(") || !hasArgCall(main, setter)) {
                unproduced.add(setter);
            }
        }
        assertEquals(DECLARED_FIELD_GAPS, unproduced,
                "事实字段的出处账对不上了。多出来的＝引擎有槽位但整条 main 没人填（读它的条件恒按默认值）；"
                        + "少掉的＝已经有人填了，把名字从缺口名单与 docs 里删掉。 实际没出处：" + unproduced);
    }

    // —— 账五：事件（注册了名字 vs 世界侧真有人派）——

    /**
     * 15 个注册事件里，今天只有 8 个真能被叫醒——这句话从前是一段人手写的表格，现在是一条等式。
     *
     * <p>判据：{@code common/} 里每个 {@code fire(} 调用点往后看到分号，那段里出现的
     * <em>已注册事件名</em>（字面量）或<em>值等于已注册事件名的字符串常量</em>（{@code Triggers.HIT}、
     * {@code TICK_EVENT} 这类）就算"这个事件有人派"。用常量表是因为派发点几乎不写字面量——
     * 直接扫 {@code "hit"} 会漏掉九个里的八个，那比没门更糟（它会假装看得见）。</p>
     *
     * <p>反向那一半<em>不</em>在这本账里：派发点叫了一个没注册的事件名，本门<em>看不见</em>。
     * 要看见它得解析 {@code fire(...)} 的参数位置（{@code Triggers.fire} 的事件在第一格、
     * {@code CardCombat.fire} 在第三格），文本扫描分不开。这件事的后果也不是"卡静默不响"而是
     * "那条派发是死代码"——卡那边写不出那个名字（加载期只认注册名），所以先认这个边界，
     * 不假装看得见。</p>
     */
    @Test
    public void everyRegisteredEventIsEitherDispatchedOrDeclaredUndispatched() throws IOException {
        Set<String> registered = eventNames();
        assertTrue(registered.size() >= 15,
                "只枚举到 " + registered.size() + " 个事件槽位——Mechanics 的槽位表变了，这本账的靶子要重钉");
        String world = textOf(mainSrc().resolve("common"));
        Set<String> dispatched = dispatchedEvents(world, registered, stringConstants());
        assertTrue(dispatched.size() >= 8,
                "只数到 " + dispatched.size() + " 个被派发的事件（" + dispatched
                        + "）——派发点的形状变了，这本账会看不见东西而不是报错");

        Set<String> undispatched = new LinkedHashSet<>(registered);
        undispatched.removeAll(dispatched);
        assertEquals(DECLARED_UNDISPATCHED_EVENTS, undispatched,
                "事件派发账对不上了。多出来的＝注册了名字但没人派（on 写它的卡永不响）；"
                        + "少掉的＝已经有人派了，把名字从缺口名单与 docs/挂点覆盖表 里删掉。 实际没人派："
                        + undispatched);
    }

    private static Set<String> eventNames() {
        Set<String> out = new LinkedHashSet<>();
        for (String id : Mechanics.ids()) {
            Mechanics.Slot slot = Mechanics.slot(id);
            if (slot != null && slot.kind() == Mechanics.Kind.EVENT) {
                out.add(id);
            }
        }
        return out;
    }

    /** main 里所有 {@code static final String NAME = "value"} 的 NAME→value（用来认派发点写的常量）。 */
    private static Map<String, String> stringConstants() throws IOException {
        Pattern declaration = Pattern.compile("static final String ([A-Z_]+) = \"([a-z_]+)\"");
        Map<String, String> out = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(mainSrc())) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher matcher = declaration.matcher(Files.readString(file));
                while (matcher.find()) {
                    out.put(matcher.group(1), matcher.group(2));
                }
            }
        }
        return out;
    }

    private static Set<String> dispatchedEvents(String world, Set<String> registered,
                                                Map<String, String> constants) {
        Set<String> dispatched = new LinkedHashSet<>();
        Pattern literal = Pattern.compile("\"([a-z_]+)\"");
        for (int at = world.indexOf("fire("); at >= 0; at = world.indexOf("fire(", at + 1)) {
            int stop = world.indexOf(';', at);
            String window = world.substring(at, stop < 0 ? Math.min(at + 260, world.length())
                    : Math.min(stop, at + 900));
            Matcher quote = literal.matcher(window);
            while (quote.find()) {
                if (registered.contains(quote.group(1))) {
                    dispatched.add(quote.group(1));
                }
            }
            for (Map.Entry<String, String> constant : constants.entrySet()) {
                if (registered.contains(constant.getValue())
                        && Pattern.compile("\\b" + constant.getKey() + "\\b").matcher(window).find()) {
                    dispatched.add(constant.getValue());
                }
            }
        }
        return dispatched;
    }

    /** 单参数（或多参）的 Builder setter；{@code with} 归账三，两个 map 形状的不归这里。 */
    private static Set<String> builderSetters() {
        Set<String> setters = new LinkedHashSet<>();
        for (java.lang.reflect.Method method : Facts.Builder.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())
                    || method.getReturnType() != Facts.Builder.class
                    || method.getName().equals("build")) {
                continue;
            }
            // layers/count 的读法与写法同名（facts.layers(id) vs builder.layers(id, n)），
            // 文本扫描分不开，硬把它们算进这本账会自证清白——那一路由 withLedger 与世界内门管。
            if (method.getName().equals("with") || method.getName().equals("layers")
                    || method.getName().equals("count")) {
                continue;
            }
            setters.add(method.getName());
        }
        assertTrue(setters.size() >= 9, "只反射到 " + setters.size() + " 个 setter（" + setters
                + "）——Facts.Builder 的形状变了，这本账的靶子也要跟着重钉");
        return setters;
    }

    private static boolean hasArgCall(String text, String setter) {
        Pattern pattern = Pattern.compile("\\." + setter + "\\(\\s*[^)\\s]");
        return pattern.matcher(text).find();
    }

    private static final Pattern FLAG_READ = Pattern.compile("flag\\(\"([a-z_]+)\"\\)");
    private static final Pattern FLAG_WRITE = Pattern.compile("with\\(\"([a-z_]+)\"\\)");

    /** 判据那一侧读到的开关名（{@code core/}）。 */
    private static Set<String> flagReads() throws IOException {
        return flagLiterals(textOf(mainSrc().resolve("core")), FLAG_READ);
    }

    /** 世界那一侧写下的开关名（{@code common/}——接管点是唯一填事实的地方）。 */
    private static Set<String> flagWrites() throws IOException {
        return flagLiterals(textOf(mainSrc().resolve("common")), FLAG_WRITE);
    }

    /** 源码里的开关名（{@code flag("target_" + text)} 那种拼接名按 TARGET_STATES 展开）。 */
    private static Set<String> flagLiterals(String text, Pattern pattern) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        if (pattern == FLAG_READ && text.contains(TARGET_FLAG_PREFIX)) {
            for (String state : Condition.TARGET_STATES) {
                names.add("target_" + state);
            }
        }
        return names;
    }

    /** 每个动作一条最小可用卡面——参数要给够，否则"兑现不出东西"与"没有执行器"分不开。 */
    private static Triggers.Result fireAction(String type) {
        Triggers.Bound entry = bound("audit_" + type,
                "{\"type\":\"trigger\",\"on\":\"hit\",\"actions\":[{\"" + type + "\":" + body(type) + "}]}");
        Map<String, StackClause> declared = new LinkedHashMap<>();
        StackClause rule = stack("audit");
        declared.put("audit", rule);
        CounterStore counters = new CounterStore();
        Triggers.Bases bases = new Triggers.Bases(10.0, 5.0, 4.0);
        Triggers.Result result = Triggers.fire(Triggers.HIT, "player-1",
                Facts.builder().attackerHp(0.1).with("fatal").build(),
                List.of(entry), counters, Map.copyOf(declared), (MechanicProfile) null, bases, 0.0);
        assertNotNull(result);
        return result;
    }

    private static String body(String type) {
        return switch (type) {
            case "stacks" -> "{\"id\":\"audit\",\"amount\":1}";
            case "damage" -> "{\"basis\":\"armor\",\"coefficient\":1.0}";
            case "knockback" -> "{\"strength\":2}";
            case "stun" -> "{\"seconds\":1}";
            case "launch" -> "{\"landing_ratio\":0.5}";
            case "slow" -> "{\"percent\":0.3,\"seconds\":2}";
            case "reflect" -> "{\"ratio\":0.5,\"basis\":\"incoming\"}";
            case "extra_resolve" -> "{\"count\":1}";
            case "copy_attack" -> "{\"radius\":3}";
            case "crit" -> "{}";
            case "lethal_immunity" -> "{\"uses\":1,\"cooldown\":20}";
            case "force_parry" -> "{\"seconds\":3}";
            case "ignore_armor" -> "{\"ratio\":0.5}";
            case "interrupt" -> "{\"seconds\":1}";
            default -> throw new IllegalStateException("动作 " + type
                    + " 没在这张账里登记最小卡面——新增动作要给 {@code body(type)} 补一行，"
                    + "否则本门会直接抛而不是给出一条可读的失败");
        };
    }

    // —— 夹具 ——

    private static Path mainSrc() {
        String value = System.getProperty("nextcard.main.src");
        assertTrue(value != null, "nextcard.main.src must be provided by the logicTest or test task");
        Path root = Path.of(value);
        assertTrue(Files.isDirectory(root), "nextcard.main.src 不是目录：" + root);
        return root;
    }

    /** 某个包目录下所有 .java 拼成一份文本（本门的扫描单位；缺目录直接红，不静默给空串）。 */
    private static String textOf(Path dir) throws IOException {
        assertTrue(Files.isDirectory(dir), "扫描目录不存在：" + dir);
        StringBuilder text = new StringBuilder();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path source : walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java")).toList()) {
                text.append(Files.readString(source)).append('\n');
            }
        }
        assertTrue(text.length() > 0, "扫描目录是空的：" + dir);
        return text.toString();
    }

    private static Triggers.Bound bound(String path, String json) {
        List<String> errors = new ArrayList<>();
        TriggerClause clause = (TriggerClause) TriggerClause.parse(
                JsonParser.parseString(json).getAsJsonObject(), errors);
        assertTrue(errors.isEmpty(), json + " 应能解析: " + errors);
        assertNotNull(clause);
        return new Triggers.Bound(new ResourceLocation("nextcard", path), clause);
    }

    private static StackClause stack(String id) {
        List<String> errors = new ArrayList<>();
        JsonObject json = JsonParser.parseString("{\"type\":\"stacks\",\"id\":\"" + id
                + "\",\"cap\":5,\"duration\":3}").getAsJsonObject();
        StackClause clause = (StackClause) StackClause.parse(json, errors);
        assertTrue(errors.isEmpty() && clause != null, errors.toString());
        return clause;
    }
}
