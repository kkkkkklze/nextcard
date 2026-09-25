package com.klze.nextcard.core.effect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.klze.nextcard.core.load.Strict;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 层数 / 资源子句：把"燃料线"声明成数据（壁障、受创、战意、过量蓄势、本次蓄力的受击计数……）。
 *
 * <pre>
 * { "type": "stacks",
 *   "id": "barrier",                                     // 内容自定义 id
 *   "scope": "persistent",                               // persistent（跨战斗保留）| action（本次动作内）
 *   "cap": 3,                                            // 缺省 = 无上限
 *   "duration": 6.0,                                     // 秒；缺省 = 永久
 *   "gain":  [ { "on": "block_success", "amount": 1, "when": [ ... ] } ],
 *   "per_stack": [ { "target": "channel.damage_reduction", "value": 0.03 } ],
 *   "on_max": [ { "stacks": { "id": "barrier", "amount": 1 } } ] }
 * </pre>
 *
 * <p><b>同一 id 可被多张卡重复声明</b>——这正是卡表的写法（每张卡各自复述这套资源）。
 * 合并规则由槽位声明：{@code cap} / {@code duration} 取最宽松（{@link Mechanics.Combine#MAX}），
 * 产出与每层映射取并集。因此"上限 3 → 10 → 解除"不需要任何特判，也不需要在某张卡上写例外。</p>
 */
public record StackClause(String id, Scope scope, double cap, double duration,
                          List<Gain> gain, List<ModifierClause> perStack, List<Action> onMax)
        implements EffectClause {

    /** 资源的存在范围。 */
    public enum Scope {
        /** 跨战斗保留（重算对账的常规形态）。 */
        PERSISTENT,
        /** 只在一次动作内有效（本次蓄力 / 本次窗口），动作结束即清零。 */
        ACTION
    }

    /** 一次产出。 */
    public record Gain(String event, double amount, List<Condition> when) {
        public Gain {
            when = List.copyOf(when);
        }
    }

    public static final double UNBOUNDED = Double.POSITIVE_INFINITY;
    private static final Set<String> KEYS = Set.of("type", "id", "scope", "cap", "duration", "gain", "per_stack", "on_max");
    private static final Set<String> GAIN_KEYS = Set.of("on", "amount", "when");

    public StackClause {
        gain = List.copyOf(gain);
        perStack = List.copyOf(perStack);
        onMax = List.copyOf(onMax);
    }

    public static StackClause parse(JsonObject body, List<String> errors) {
        errors.addAll(Strict.unknownKeys(body, KEYS, "stacks"));
        if (!body.has("id") || !body.get("id").isJsonPrimitive() || !body.getAsJsonPrimitive("id").isString()) {
            errors.add("stacks needs a string id");
            return null;
        }
        String id = body.get("id").getAsString();
        if (!id.matches("[a-z0-9_.\\-]+")) {
            errors.add("stacks id must match [a-z0-9_.-]+: " + id);
            return null;
        }
        Scope scope = Scope.PERSISTENT;
        if (body.has("scope")) {
            String text = body.get("scope").getAsString();
            if (text.equals("action")) {
                scope = Scope.ACTION;
            } else if (!text.equals("persistent")) {
                errors.add("stacks scope must be persistent or action, got " + text);
                return null;
            }
        }
        double cap = body.has("cap") ? body.get("cap").getAsDouble() : UNBOUNDED;
        if (cap <= 0) {
            errors.add("stacks cap must be positive (omit the field for unbounded): " + id);
            return null;
        }
        double duration = body.has("duration") ? body.get("duration").getAsDouble() : 0.0;
        if (duration < 0) {
            errors.add("stacks duration must not be negative: " + id);
            return null;
        }
        List<Gain> gains = new ArrayList<>();
        if (body.has("gain")) {
            JsonArray array = body.getAsJsonArray("gain");
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    errors.add("stacks gain entries must be objects");
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                errors.addAll(Strict.unknownKeys(entry, GAIN_KEYS, "stacks gain"));
                if (!entry.has("on") || Mechanics.slot(entry.get("on").getAsString()) == null
                        || Mechanics.slot(entry.get("on").getAsString()).kind() != Mechanics.Kind.EVENT) {
                    errors.add("stacks gain on must be a registered event: " + entry.get("on"));
                    continue;
                }
                double amount = entry.has("amount") ? entry.get("amount").getAsDouble() : 1.0;
                List<Condition> when = new ArrayList<>();
                if (!parseConditions(entry, "when", when, errors)) {
                    continue;
                }
                gains.add(new Gain(entry.get("on").getAsString(), amount, when));
            }
        }
        List<ModifierClause> perStack = new ArrayList<>();
        if (body.has("per_stack")) {
            for (JsonElement element : body.getAsJsonArray("per_stack")) {
                if (!element.isJsonObject()) {
                    errors.add("stacks per_stack entries must be objects");
                    continue;
                }
                ModifierClause clause = ModifierClause.parse(element.getAsJsonObject(), errors);
                if (clause != null) {
                    perStack.add(clause);
                }
            }
        }
        List<Action> onMax = new ArrayList<>();
        if (body.has("on_max")) {
            for (JsonElement element : body.getAsJsonArray("on_max")) {
                Action action = Action.parse(element, errors);
                if (action != null) {
                    onMax.add(action);
                }
            }
        }
        return new StackClause(id, scope, cap, duration, gains, perStack, onMax);
    }

    static boolean parseConditions(JsonObject owner, String field, List<Condition> out, List<String> errors) {
        if (!owner.has(field)) {
            return true;
        }
        JsonElement element = owner.get(field);
        if (!element.isJsonArray()) {
            errors.add(field + " must be an array");
            return false;
        }
        for (JsonElement entry : element.getAsJsonArray()) {
            if (!entry.isJsonObject()) {
                errors.add(field + " entries must be objects");
                return false;
            }
            Condition condition = Condition.parse(entry.getAsJsonObject(), errors);
            if (condition != null) {
                out.add(condition);
            }
        }
        return true;
    }
}
