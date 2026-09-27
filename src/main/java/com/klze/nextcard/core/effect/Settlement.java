package com.klze.nextcard.core.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * 一次命中的完整结算：<b>攻方管线算完，把交付物交给守方管线跑自己的账</b>。
 *
 * <p>分成两条而不是一条长表，是因为两边的主语不同：乘区属于攻方（"我这一发多疼"），
 * 减免属于守方（"我这边谁吃了一口"）。合成一条就会出现"守方的减伤被攻方的暴击又放大一次"
 * 这类错——顺序上把减免排在暴击之后只是碰巧对，语义上根本不是一回事。
 * 分界点 {@link AttackPipeline.Delivery} 也是卡面文案的分界：{@code 《00》}里"额外造成一次
 * 30% 的独立伤害"写在攻方，"护盾吃伤害"写在守方。</p>
 *
 * <p>本类不认识实体、不访问世界：接管点（{@code common/combat/CardCombat}）负责把两边
 * 的折好的快照变成输入，判据留在无头测试里。</p>
 */
public final class Settlement {

    private Settlement() {
    }

    /** 两段留痕拼成一条链，读的人不用自己对接两个列表。 */
    public record Result(AttackPipeline.Delivery delivery, DefencePipeline.Result defence) {

        public double value() {
            return defence.landed();
        }

        /** 完整一条链的文本留痕（"基础 → … → 交付 → 护盾 → … → 落地"）。 */
        public List<String> trace() {
            List<String> lines = new ArrayList<>(delivery.traces().size() + defence.traces().size());
            for (StepTrace trace : delivery.traces()) {
                lines.add("攻 " + trace);
            }
            for (StepTrace trace : defence.traces()) {
                lines.add("守 " + trace);
            }
            for (String bypass : defence.bypassed()) {
                lines.add("守 " + bypass);
            }
            return List.copyOf(lines);
        }

        public String summary() {
            return "交付 " + delivery.summary() + " → 落地 "
                    + StepTrace.formatted(defence.landed());
        }
    }

    /**
     * 跑完整链。
     *
     * @param attack 攻方的乘区输入（{@code base} 缺失会在这里抛错，绝不默认 0）
     * @param defence 守方的来源清单；null 表示守方没有任何可生效的账（仍然要跑一次，留交付痕）
     */
    public static Result resolve(AttackPipeline.Input attack, @Nullable DefencePipeline.Options defence) {
        AttackPipeline.Delivery delivery = AttackPipeline.resolve(attack);
        DefencePipeline.Result mitigated = DefencePipeline.run(delivery,
                defence == null ? DefencePipeline.Options.NONE : defence);
        return new Result(delivery, mitigated);
    }

    /** 守方什么都没有的常态（纯攻方一次命中）。 */
    public static Result resolve(AttackPipeline.Input attack) {
        return resolve(attack, null);
    }

    /**
     * 只有基础值、其余乘区都为 1/0 的输入。
     *
     * <p>{@code LivingDamageEvent} 给的金额已经过了原版护甲与抗性，所以它只能当"基础值"进来，
     * 不能再进守方的减免段——那正是"同一个减免生效两次"的入口。</p>
     */
    public static AttackPipeline.Input plain(double base) {
        return new AttackPipeline.Input(base, 1.0, null, Map.of(), 0.0, 0.0, 0.0, 1.0, 0.0,
                false, 0.0, 0.0, Set.of());
    }
}
