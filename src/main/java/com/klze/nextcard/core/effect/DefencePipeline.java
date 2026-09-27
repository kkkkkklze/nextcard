package com.klze.nextcard.core.effect;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * 守方管线：一发<em>已经由攻方交付</em>的伤害，在守方这边怎么被改小的有序表。
 *
 * <p>形状照姊妹工程求仙问道的 {@code DefencePipeline}（D-048 裁定）：<b>顺序由引擎写死</b>，
 * 内容只能声明"往哪一步写数"，没有优先级旋钮；能把数打成 0 的只有第①步否决，而且它必须带归因；
 * 每一步都留「之前 → 之后 → 为什么」。</p>
 *
 * <pre>
 * ① 免疫/否决   卡面条件成立 → 0（《00》不屈"免疫该次伤害"、弹反誓约"免疫一次"）
 * ② 护盾吸收    定值、逐来源依次吃（《02》L346：护盾＝定值、先于生命承受、有独立持续时间）
 * ③ 固定减伤    点数式相加，夹到 ≥ 0
 * ④ 比例减免    梯度下降 1 − ∏(1−rᵢ)（《02》：两个 20% 是 −36% 不是 −40%；格挡减伤作为一个来源进这里）
 * ⑤ 转移/吸收   【预留】要把伤害记到别的对象头上，需要持久对象，尚未落地——不写空实现（假门）
 * ⑥ 交付        扣血之前的那一个值
 * </pre>
 *
 * <p><b>为什么没有"护甲曲线"与"抗性阶梯"两步</b>：接管点是 {@code LivingDamageEvent}，
 * 它发生在原版护甲减免与抗性等级<em>之后</em>。再算一遍就是让同一个减免生效两次——
 * 那是层次倍率只能生效一次这条铁律的反面。{@code armor} / {@code resistance} 通道因此
 * 落成原版 AttributeModifier（见 {@link Mechanics#VANILLA_CHANNELS}），不进本管线。</p>
 *
 * <p><b>本类不认识实体、不访问世界</b>：所以"顺序对不对""谁吃了多少"能在纯逻辑里断言。</p>
 */
public final class DefencePipeline {

    private DefencePipeline() {
    }

    /** 引擎写死的有序表，顺序＝声明顺序。 */
    public enum Step implements PipelineStep {

        /** ① 免疫/否决：三张卡证实的合法否决（不屈、弹反誓约、向死而生的控制免疫）。 */
        VETO("免疫/否决"),

        /** ② 护盾吸收：有上限的额外承受量，扣完就没了——它表达不了"免疫"。 */
        SHIELD("护盾吸收"),

        /** ③ 固定减伤：点数式（相加，夹到 ≥ 0）。 */
        FLAT_REDUCTION("固定减伤"),

        /** ④ 比例减免：梯度下降（{@code 1 − ∏(1−rᵢ)}）。 */
        RATIO_REDUCTION("比例减免"),

        /** ⑤ 转移/吸收：<b>预留</b>（见类注释，不写空实现）。 */
        DIVERSION("转移/吸收（预留）"),

        /** ⑥ 交付：落地值。 */
        DELIVER("交付");

        private final String display;

        Step(String display) {
            this.display = display;
        }

        @Override
        public String display() {
            return display;
        }
    }

    /**
     * 一个有名字、有数值的来源。
     *
     * <p>为什么不是一个合起来的数字：守方的账必须说得出"这一口是谁吃的""这 36% 是哪两处给的"。
     * 护盾尤其要按来源扣（吃完就没了），减免则要能回答"哪个来源被穿透跳过了"。</p>
     */
    public record Source(String name, double amount) {

        public Source {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("来源必须有名字（留痕要能归因）");
            }
        }

        @Override
        public String toString() {
            return name + " " + StepTrace.formatted(amount);
        }
    }

    /**
     * 守方自己有哪些来源（不含攻方交付的那一发）。
     *
     * @param vetoReason      第①步成立时写"为什么免疫"（由调用方的判定条件给出）；null / 空串表示没成立
     * @param shieldPoints    护盾点数来源（定值，逐来源吃）
     * @param flatReductions  固定减伤来源（点数）
     * @param ratioReductions 比例减伤来源（0 ≤ r &lt; 1，小数；格挡减伤作为一个来源进这里）
     */
    public record Options(@Nullable String vetoReason, List<Source> shieldPoints,
                          List<Source> flatReductions, List<Source> ratioReductions) {

        public static final Options NONE = new Options(null, List.of(), List.of(), List.of());

        public Options {
            shieldPoints = guard(shieldPoints, "护盾点数", 0.0, Double.POSITIVE_INFINITY);
            flatReductions = guard(flatReductions, "固定减伤", 0.0, Double.POSITIVE_INFINITY);
            ratioReductions = guard(ratioReductions, "比例减伤", 0.0, 1.0);
        }

        /**
         * 「禁止绝对」做成会失败的判据：单个来源的减伤写不到 100%。
         *
         * <p>能一句话把伤害归零的只有第①步免疫（它带归因、并且真的是一次否决）。减免来源想归零，
         * 要么走免疫，要么多个来源叠（梯度下降永远到不了 1）。负数同理被拒——"受到的伤害增加"
         * 是攻方的一个乘区，不该伪装成一个负的减伤，否则会绕过留痕里的位置信息。</p>
         */
        private static List<Source> guard(@Nullable List<Source> sources, String label, double min, double max) {
            if (sources == null) {
                return List.of();
            }
            List<Source> copy = List.copyOf(sources);
            for (Source source : copy) {
                if (!(source.amount() >= min) || source.amount() >= max) {
                    throw new IllegalArgumentException(label + "来源「" + source.name() + "」的值必须在 ["
                            + min + ", " + max + ") 内，实际 " + source.amount()
                            + "——归零请走免疫否决，加深伤害请记在攻方乘区");
                }
            }
            return copy;
        }
    }

    /** 一次防御结算的输入：攻方交付的那一发 ＋ 守方的账。 */
    public record Input(AttackPipeline.Delivery incoming, Options options) {

        public Input {
            options = options == null ? Options.NONE : options;
        }

        public double incomingValue() {
            return incoming.value();
        }
    }

    /** 一次防御结算的结果。 */
    public record Result(List<StepTrace> traces, double landed, double remainingRate,
                         List<Source> shieldEaten, List<String> bypassed) {

        public Result {
            traces = List.copyOf(traces);
            shieldEaten = List.copyOf(shieldEaten);
            bypassed = List.copyOf(bypassed);
        }

        /** 归因：某一步的留痕（没跑 / 没改数则为空）。 */
        public @Nullable StepTrace traceOf(Step step) {
            for (StepTrace trace : traces) {
                if (trace.step() == step) {
                    return trace;
                }
            }
            return null;
        }

        /** 第②步一共吃掉了多少。 */
        public double shieldAbsorbed() {
            double total = 0.0;
            for (Source eaten : shieldEaten) {
                total += eaten.amount();
            }
            return total;
        }

        public boolean mitigates() {
            return remainingRate < 1.0;
        }

        /** 一行摘要（日志与自证门读它）。 */
        public String summary() {
            return "剩余率 " + StepTrace.formatted(remainingRate) + "：" + traces
                    + (bypassed.isEmpty() ? "" : "；跳过 " + bypassed);
        }
    }

    public static Result run(AttackPipeline.Delivery incoming) {
        return run(new Input(incoming, Options.NONE));
    }

    public static Result run(AttackPipeline.Delivery incoming, Options options) {
        return run(new Input(incoming, options));
    }

    /** 跑完整条管线（顺序＝{@link Step} 的声明顺序，一步都不跳）。 */
    public static Result run(Input input) {
        Options options = input.options();
        List<StepTrace> traces = new ArrayList<>(6);
        List<String> bypassed = new ArrayList<>(2);
        List<Source> shieldEaten = new ArrayList<>(options.shieldPoints().size());

        double start = input.incomingValue();
        if (start < 0) {
            throw new IllegalArgumentException("攻方交付值不得为负: " + start);
        }
        double amount = start;

        // ① 免疫/否决：唯一能把数打成 0 的一步，且必须带归因。
        if (options.vetoReason() != null && !options.vetoReason().isEmpty()) {
            traces.add(new StepTrace(Step.VETO, amount, 0.0, "免疫：" + options.vetoReason()));
            amount = 0.0;
        }

        // ② 护盾吸收：逐来源各吃自己那一份；吃完就没了，所以吃掉多少要交回调用点去扣账。
        if (input.incoming().penetrates(AttackPipeline.Penetration.IGNORE_SHIELD)) {
            bypassed.add(Step.SHIELD.display() + "：被「" + AttackPipeline.Penetration.IGNORE_SHIELD.display() + "」穿透跳过");
        } else if (amount > 0) {
            double beforeShield = amount;
            for (Source source : options.shieldPoints()) {
                if (amount <= 0 || source.amount() <= 0) {
                    continue;
                }
                double bite = Math.min(amount, source.amount());
                amount -= bite;
                shieldEaten.add(new Source(source.name(), bite));
            }
            if (!shieldEaten.isEmpty()) {
                traces.add(new StepTrace(Step.SHIELD, beforeShield, amount, "护盾吃下 " + shieldEaten));
            }
        }

        // 减免两步都能被"无视减伤"穿透跳过（《00》处决：不结算护甲与减伤）。
        boolean reductionsIgnored = input.incoming().penetrates(AttackPipeline.Penetration.IGNORE_REDUCTION);

        // ③ 固定减伤：点数相加，夹到 ≥ 0。
        if (reductionsIgnored) {
            bypassed.add(Step.FLAT_REDUCTION.display() + "：被「"
                    + AttackPipeline.Penetration.IGNORE_REDUCTION.display() + "」穿透跳过");
        } else if (amount > 0 && !options.flatReductions().isEmpty()) {
            double flat = 0.0;
            for (Source source : options.flatReductions()) {
                flat += source.amount();
            }
            double after = Math.max(0.0, amount - flat);
            traces.add(new StepTrace(Step.FLAT_REDUCTION, amount, after, "点数额 " + options.flatReductions()));
            amount = after;
        }

        // ④ 比例减免：梯度下降 1 − ∏(1−rᵢ)。合并成一步留痕，但每个来源都在理由里点名。
        if (reductionsIgnored) {
            bypassed.add(Step.RATIO_REDUCTION.display() + "：被「"
                    + AttackPipeline.Penetration.IGNORE_REDUCTION.display() + "」穿透跳过");
        } else if (amount > 0 && !options.ratioReductions().isEmpty()) {
            double survivor = 1.0;
            for (Source source : options.ratioReductions()) {
                survivor *= 1.0 - source.amount();
            }
            double cut = 1.0 - survivor;
            double after = amount * survivor;
            traces.add(new StepTrace(Step.RATIO_REDUCTION, amount, after,
                    "合计削 " + StepTrace.formatted(cut) + "，来源 " + options.ratioReductions()
                            + "（梯度下降，不是相加）"));
            amount = after;
        }

        // ⑤ 转移/吸收：预留（需要持久对象：把这一口记到别的实体/容器头上）。
        //    不写"什么都不做"的空实现——那会让读留痕的人以为它跑过了。

        // ⑥ 交付。
        traces.add(new StepTrace(Step.DELIVER, amount, amount, ""));
        double remaining = start == 0 ? 1.0 : amount / start;
        return new Result(kept(traces), amount, remaining, shieldEaten, bypassed);
    }

    /**
     * 只留"真的改过数"的步（外加最后的交付）。
     *
     * <p>判据是 {@link StepTrace#changed()}：一步跑过但没改变任何数（守方没有护盾时的护盾段）
     * 不该占一行，否则"哪些步生效了"要靠人再读一遍数字。被跳过的步另有 {@link Result#bypassed()}，
     * 那里恰恰要留下"为什么没生效"。</p>
     */
    private static List<StepTrace> kept(List<StepTrace> traces) {
        List<StepTrace> kept = new ArrayList<>(traces.size());
        for (StepTrace trace : traces) {
            if (trace.changed() || trace.step() == Step.DELIVER) {
                kept.add(trace);
            }
        }
        return kept;
    }
}
