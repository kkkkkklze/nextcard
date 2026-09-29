package com.klze.nextcard.core.effect;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.Nullable;

/**
 * 攻方管线：一次攻击「该报出去多少」的有序表（口径来自内容侧《02-名词表》4.1 与《00-总览大纲》若干条）。
 *
 * <p>公式写死成一条有序表，不是一堆散落的乘法：
 * {@code 基础 × 系数 × (1+全伤) × (1+本分类加成) × (1+专属buff) × (1+特殊伤害) × 暴击
 * × (1+方向增伤) + 只加本伤害源 + 追加结算 → 交付}。
 * <b>顺序＝{@link Step} 的声明顺序</b>，改动必须有裁定（照姊妹工程求仙问道的纪律：内容只能声明
 * "往哪一步写数"，不能自己插队）。</p>
 *
 * <p>减伤不在这里——那是守方自己的账，见 {@link DefencePipeline}。两段的分界点就是
 * {@link Delivery}：攻方算完把「数值 + 分类 + 穿透声明」交出去，守方拿着它跑自己的管线。</p>
 *
 * <p>几条内容侧口径被做成<b>会失败</b>的判据，而不是注释：</p>
 * <ul>
 *   <li><b>武器分类六选一</b>：靠"这一次攻击只有一个分类"来表达（{@code attackClass} 是单值），
 *       所以结构上就不可能同时吃到两个分类加成——不靠事后检查，也不靠"取第一个"。</li>
 *   <li><b>方向增伤封顶 120%</b>：只有卡面声明"超限"（{@code capExempted}）才允许突破。</li>
 *   <li><b>基础数值不得默认 0</b>：缺失即抛错；<b>吃不到的乘区按 0</b>：分类对不上就是 0，不报错。</li>
 *   <li><b>"只加本伤害源"与"追加结算"不进任何乘区</b>：它们在所有乘法之后加。</li>
 * </ul>
 *
 * <p>{@link Delivery#traces()} 逐步记「之前 → 之后」：内容作者问"这张卡为什么只打出这点伤害"，
 * 回答要能指到具体某一步，而不是让人反推公式。</p>
 */
public final class AttackPipeline {

    /** 方向增伤的封顶（120%）。 */
    public static final double DIRECTION_BONUS_CAP = 1.2;

    private AttackPipeline() {
    }

    /**
     * 引擎写死的有序表，顺序＝声明顺序。
     *
     * <p>为什么方向增伤在所有乘区的最后、而尾部加法在乘区之外：卡面把"额外造成 30% 独立伤害"
     * （背刺）与"只加本伤害源"都写成<em>不被别的乘区放大</em>的独立量，一旦放进乘区，
     * 一张 +100% 全伤的卡就会把定值加成也翻倍——那是第二次生效，同层次倍率只能算一次的道理。</p>
     */
    public enum Step implements PipelineStep {
        BASE("基础值"),
        SYSTEM_COEFFICIENT("系统系数"),
        ALL_DAMAGE("全伤乘区"),
        ATTACK_CLASS("武器分类"),
        OWN_BUFF("专属buff"),
        SPECIAL_DAMAGE("特殊伤害"),
        CRIT("暴击"),
        DIRECTION("方向增伤"),
        FLAT_THIS_SOURCE("只加本伤害源"),
        EXTRA_SETTLEMENT("追加结算"),

        /** 交付：交给守方管线的东西（数值 + 分类 + 穿透声明）。 */
        DELIVER("交付");

        private final String display;

        Step(String display) {
            this.display = display;
        }

        /** 中文名（留痕与日志用；判据一律按 {@link #ordinal()}）。 */
        public String display() {
            return display;
        }
    }

    /**
     * @param base             基础值（缺失即错误，绝不默认 0）
     * @param coefficient      系统系数
     * @param attackClass      这次攻击属于哪个分类（单值＝六选一）；null 表示不带分类
     * @param classBonuses     分类 → 加成，由效果层折好交进来
     * @param allDamage        全伤乘区
     * @param ownBuff          专属 buff 乘区
     * @param specialDamage    特殊伤害乘区
     * @param critMultiplier   暴击乘区（不暴击时给 1.0）
     * @param directionBonus   方向增伤（小数，0.2 = +20%）
     * @param capExempted      卡面是否声明"超限"
     * @param flatThisSource   只加本伤害源（乘区之外）
     * @param extraSettlements 追加结算（乘区之外）
     * @param penetration      这次攻击声明的穿透（《00》：横扫"无视目标的格挡与护盾"、
     *                         处决"不结算护甲与减伤"）。没有卡面通道写它之前，调用方传空集。
     * @param directionApplicable 这一发的<em>接触</em>是否满足方向条件（背后 120° 之类由调用方
     *                         从 {@link Facts} 判好交进来）。不成立时卡面给的方向增伤值<em>不进乘区</em>，
     *                         但留痕会写"未生效"，好让人一眼看出是"没吃到"而不是"没写"。
     * @param crits            这一发暴击<em>几次</em>（{@link CritRules} 算出来的）。
     *                         乘区里用的是 {@code critMultiplier}（已是总系数），这一格只为留痕：
     *                         溢出连暴时"× 暴击 1.69"要能说出是 1.3 乘了两遍。
     */
    public record Input(double base, double coefficient, @Nullable String attackClass,
                        Map<String, Double> classBonuses, double allDamage, double ownBuff,
                        double specialDamage, double critMultiplier, double directionBonus,
                        boolean capExempted, double flatThisSource, double extraSettlements,
                        Set<Penetration> penetration, boolean directionApplicable, int crits) {

        public Input {
            classBonuses = new TreeMap<>(classBonuses);
            penetration = penetration == null ? Set.of() : Set.copyOf(penetration);
            crits = Math.max(0, crits);
        }

        /** 不关心方向条件、也不报暴击次数的调用点（纯数值推演、蒙特卡洛）：按"方向已成立"算。 */
        public Input(double base, double coefficient, @Nullable String attackClass,
                     Map<String, Double> classBonuses, double allDamage, double ownBuff,
                     double specialDamage, double critMultiplier, double directionBonus,
                     boolean capExempted, double flatThisSource, double extraSettlements,
                     Set<Penetration> penetration) {
            this(base, coefficient, attackClass, classBonuses, allDamage, ownBuff, specialDamage,
                    critMultiplier, directionBonus, capExempted, flatThisSource, extraSettlements,
                    penetration, true, critsOf(critMultiplier));
        }

        /** 只不报暴击次数的那一个（次数按"乘区不为 1 就是暴击了一次"推）。 */
        public Input(double base, double coefficient, @Nullable String attackClass,
                     Map<String, Double> classBonuses, double allDamage, double ownBuff,
                     double specialDamage, double critMultiplier, double directionBonus,
                     boolean capExempted, double flatThisSource, double extraSettlements,
                     Set<Penetration> penetration, boolean directionApplicable) {
            this(base, coefficient, attackClass, classBonuses, allDamage, ownBuff, specialDamage,
                    critMultiplier, directionBonus, capExempted, flatThisSource, extraSettlements,
                    penetration, directionApplicable, critsOf(critMultiplier));
        }

        private static int critsOf(double critMultiplier) {
            return critMultiplier == 1.0 ? 0 : 1;
        }

        /** 六选一：只有这一格能用，其余分类的加成对本次攻击不产生影响。 */
        public double classBonus() {
            return attackClass == null ? 0.0 : classBonuses.getOrDefault(attackClass, 0.0);
        }
    }

    /**
     * 穿透声明：攻方**能说出口**的"跳过守方哪一步"。
     *
     * <p>它是攻方的声明，不是守方的例外——守方只负责按声明跳过并留痕，所以"无视护盾"这种卡
     * 不需要在守方加特判。三类都<b>跳不过第①步否决</b>：《00》里"血量锁定 / 免疫阶段无法触发"
     * 就是这条口径（免疫是合法否决，穿透只是省掉减免账）。</p>
     */
    public enum Penetration {
        /** 跳过守方护盾吸收（横扫）。 */
        IGNORE_SHIELD("无视护盾"),

        /** 跳过守方的固定与比例减免（处决"不结算护甲与减伤"）。 */
        IGNORE_REDUCTION("无视减伤"),

        /** 跳过格挡判定（横扫）：格挡账在盾反子系统里，由调用方决定这一口是否算作被挡。 */
        IGNORE_BLOCK("无视格挡");

        private final String display;

        Penetration(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }
    }

    /**
     * 交付给守方的东西。
     *
     * @param value       这一发的落地前数值
     * @param attackClass 分类（守方按它判"免疫的是哪一类"）
     * @param penetration 攻方声明的穿透
     */
    public record Delivery(double value, @Nullable String attackClass, Set<Penetration> penetration,
                           List<StepTrace> traces) {

        public Delivery {
            penetration = penetration == null ? Set.of() : Set.copyOf(penetration);
            traces = List.copyOf(traces);
        }

        public boolean penetrates(Penetration kind) {
            return penetration.contains(kind);
        }

        /** 一行摘要（GameTest 与日志读它）。 */
        public String summary() {
            return StepTrace.formatted(value) + (penetration.isEmpty() ? "" : " " + penetration);
        }

        /** 留给旧文案用的扁平留痕行（"哪一步把数改成了什么"）。 */
        public List<String> traceText() {
            List<String> lines = new ArrayList<>(traces.size());
            for (StepTrace trace : traces) {
                lines.add(trace.toString());
            }
            return List.copyOf(lines);
        }
    }

    public static Delivery resolve(Input input) {
        if (!(input.base() > 0)) {
            throw new IllegalArgumentException("基础数值不得默认 0，必须由调用点点名给出: " + input.base());
        }
        List<StepTrace> traces = new ArrayList<>(8);
        double value = input.base();
        traces.add(new StepTrace(Step.BASE, value, value, ""));

        value = stepped(traces, Step.SYSTEM_COEFFICIENT, value, value * input.coefficient(),
                "× 系数 " + input.coefficient());

        value = stepped(traces, Step.ALL_DAMAGE, value, value * (1.0 + input.allDamage()),
                "× (1+全伤 " + input.allDamage() + ")");

        double classBonus = input.classBonus();
        value = stepped(traces, Step.ATTACK_CLASS, value, value * (1.0 + classBonus),
                "× (1+" + (classBonus == 0.0 ? "0" : input.attackClass() + " " + classBonus) + ")");

        value = stepped(traces, Step.OWN_BUFF, value, value * (1.0 + input.ownBuff()),
                "× (1+专属buff " + input.ownBuff() + ")");

        value = stepped(traces, Step.SPECIAL_DAMAGE, value, value * (1.0 + input.specialDamage()),
                "× (1+特殊伤害 " + input.specialDamage() + ")");

        value = stepped(traces, Step.CRIT, value, value * input.critMultiplier(),
                "× 暴击 " + input.critMultiplier() + (input.crits() > 1
                        ? "（" + input.crits() + " 次：暴击率超过 100%，溢出部分再暴击）" : ""));

        double direction = input.directionBonus();
        String directionNote = "× (1+方向增伤 " + direction + ")";
        if (!input.directionApplicable()) {
            // 值本来是卡面给的，接触不成立就不进这一格——但要留痕说清是"没吃到"而不是"没写"
            directionNote = "方向增伤 " + direction + " 未生效（这一发的接触不满足方向条件）";
            direction = 0.0;
        } else if (!input.capExempted() && direction > DIRECTION_BONUS_CAP) {
            directionNote += " 被封顶到 " + DIRECTION_BONUS_CAP + "（卡面未声明超限）";
            direction = DIRECTION_BONUS_CAP;
        }
        value = stepped(traces, Step.DIRECTION, value, value * (1.0 + direction), directionNote);

        if (input.flatThisSource() != 0.0) {
            value = stepped(traces, Step.FLAT_THIS_SOURCE, value, value + input.flatThisSource(),
                    "+ 只加本伤害源 " + input.flatThisSource());
        }
        if (input.extraSettlements() != 0.0) {
            value = stepped(traces, Step.EXTRA_SETTLEMENT, value, value + input.extraSettlements(),
                    "+ 追加结算 " + input.extraSettlements());
        }

        traces.add(new StepTrace(Step.DELIVER, value, value,
                input.penetration().isEmpty() ? "" : "穿透 " + describe(input.penetration())));
        return new Delivery(value, input.attackClass(), input.penetration(), List.copyOf(traces));
    }

    private static String describe(Set<Penetration> penetration) {
        List<String> names = new ArrayList<>();
        for (Penetration kind : penetration) {
            names.add(kind.display());
        }
        return String.join("、", names);
    }

    private static double stepped(List<StepTrace> traces, Step step, double before, double after, String note) {
        traces.add(new StepTrace(step, before, after, note));
        return after;
    }

    /** 穿透集合的便利构造（保持顺序，便于留痕稳定）。 */
    public static Set<Penetration> penetration(Penetration... kinds) {
        Set<Penetration> set = new LinkedHashSet<>();
        for (Penetration kind : kinds) {
            set.add(kind);
        }
        return Set.copyOf(set);
    }
}
