package com.klze.nextcard.core.effect;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.klze.nextcard.core.load.Strict;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * 机制修饰子句（§5.4 形状，首板卡表第一批落成）：改写一个槽位，或给一个数值通道加值
 * （卡面「附带数值」也走这里——它本来就是对通道的一次加值，不值得单独造字段）。
 *
 * <pre>
 * { "type": "mechanic_modifier",
 *   "target": "window.length",                                  // 槽位（见 Mechanics.slot）
 *   "value": 0.3,                                               // 常量来源
 *   "source": { "counter": "war_intent", "per_layer": 0.03 },    // 或每层来源
 *   "uncapped": true,                                            // 或"解除上限"（仅上限类槽位）
 *   "when": [ { "hp_below": 0.3 } ] }                            // 可选条件
 * </pre>
 *
 * <p><b>没有 {@code op} 字段</b>：合并方式由槽位自己声明（{@link Mechanics.Combine}）。
 * 卡面想用另一种合并方式，只能改用另一个槽位（例如"延长项"用 {@code window.length}、
 * "乘区"用 {@code window.scale}）——这是"不许为某张卡写专门规则"的结构保证。</p>
 *
 * <p>{@code uncapped} 是唯一不能由数字表达的一种改写（"不再有上限"），所以它是独立形态
 * 而不是一个很大的数：只有上限类槽位（合成方式为 {@link Mechanics.Combine#MAX}）可以解除上限，
 * 在其它槽位上写 {@code uncapped} 是加载错误。</p>
 */
public record ModifierClause(String target, double value, String text, boolean uncapped,
                             String sourceCounter, double perLayer,
                             List<Condition> when) implements EffectClause {

    private static final Set<String> KEYS = Set.of("type", "target", "value", "uncapped", "source", "when");
    private static final Set<String> SOURCE_KEYS = Set.of("counter", "per_layer");

    public ModifierClause {
        when = List.copyOf(when);
    }

    /** 不解除上限的数值改写（测试与内容解析两条路都走它）。 */
    public ModifierClause(String target, double value, String sourceCounter, double perLayer,
                          List<Condition> when) {
        this(target, value, "", false, sourceCounter, perLayer, when);
    }

    /** 枚举类槽位的改写（{@code block.angle} 的档位）。 */
    public static ModifierClause ofText(String target, String text) {
        return new ModifierClause(target, 0.0, text, false, "", 0.0, List.of());
    }

    public static ModifierClause parse(JsonObject body, List<String> errors) {
        errors.addAll(Strict.unknownKeys(body, KEYS, "mechanic_modifier"));
        if (!body.has("target") || !body.get("target").isJsonPrimitive()
                || !body.getAsJsonPrimitive("target").isString()) {
            errors.add("mechanic_modifier needs a string target");
            return null;
        }
        String target = body.get("target").getAsString();
        Mechanics.Slot slot = Mechanics.slot(target);
        if (slot == null) {
            errors.add("unknown mechanic slot: " + target);
            return null;
        }
        double value = 0.0;
        String text = "";
        String counter = "";
        double perLayer = 0.0;
        boolean uncapped = body.has("uncapped") && body.get("uncapped").getAsBoolean();
        boolean hasValue = body.has("value");
        boolean hasSource = body.has("source");
        int forms = (hasValue ? 1 : 0) + (hasSource ? 1 : 0) + (uncapped ? 1 : 0);
        if (forms != 1) {
            errors.add("mechanic_modifier " + target
                    + " needs exactly one of value / source / uncapped");
            return null;
        }
        if (uncapped && !Mechanics.isCap(slot)) {
            errors.add("mechanic_modifier " + target + " cannot be uncapped: only cap slots can be unlocked");
            return null;
        }
        if (hasValue) {
            JsonElement raw = body.get("value");
            boolean isText = raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString();
            if (slot.type() == Mechanics.ParamType.ENUM) {
                if (!isText) {
                    errors.add("mechanic_modifier " + target + " is an enum slot and needs a string value");
                    return null;
                }
                text = raw.getAsString();
                if (target.equals("block.angle") && !Mechanics.ANGLE_VALUES.contains(text)) {
                    errors.add("mechanic_modifier block.angle must be one of " + Mechanics.ANGLE_VALUES
                            + ", got " + text);
                    return null;
                }
            } else {
                if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()) {
                    errors.add("mechanic_modifier " + target + " value must be a number");
                    return null;
                }
                value = raw.getAsDouble();
            }
        } else if (hasSource) {
            JsonElement source = body.get("source");
            if (!source.isJsonObject()) {
                errors.add("mechanic_modifier " + target + " source must be an object");
                return null;
            }
            JsonObject sourceBody = source.getAsJsonObject();
            errors.addAll(Strict.unknownKeys(sourceBody, SOURCE_KEYS, "mechanic_modifier source"));
            if (!sourceBody.has("counter") || !sourceBody.has("per_layer")) {
                errors.add("mechanic_modifier source needs {counter, per_layer}");
                return null;
            }
            counter = sourceBody.get("counter").getAsString();
            perLayer = sourceBody.get("per_layer").getAsDouble();
        }
        List<Condition> conditions = new ArrayList<>();
        if (!StackClause.parseConditions(body, "when", conditions, errors)) {
            return null;
        }
        return new ModifierClause(target, value, text, uncapped, counter, perLayer, conditions);
    }

    public boolean isPerLayer() {
        return !sourceCounter.isEmpty();
    }

    /** 是否写的是枚举档位而不是数字。 */
    public boolean isText() {
        return !text.isEmpty();
    }

    /** 按给定层数求本次改写的数值（每层来源 = 每层值 × 层数）。纯函数，便于无头测试。 */
    public double valueFor(ToIntFunction<String> layerCount) {
        return isPerLayer() ? perLayer * layerCount.applyAsInt(sourceCounter) : value;
    }

    /** 是否只在给定条件成立时生效（条件判定在运行期；这里只说明本改写是否无条件）。 */
    public boolean unconditional() {
        return when.isEmpty();
    }
}
