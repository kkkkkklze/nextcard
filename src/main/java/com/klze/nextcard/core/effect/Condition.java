package com.klze.nextcard.core.effect;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 条件（触发与改写的共用语汇）。四种形态共用一个 record，不给每个条件造一个类型：
 * 数值型（{@code hp_below: 0.3}）、枚举型（{@code angle: "front"}）、开关型
 * （{@code charge_active: true}）、引用型（{@code stacks: {"id": "...", "at_least": 3}}）。
 *
 * <p>条件只描述"什么时候成立"，不含任何数值计算；判定发生在 M2 的运行期，加载期只做形态校验。</p>
 */
public record Condition(String key, double number, String text, String stackId, String event) {

    /** 已注册的条件键。 */
    public static final Set<String> KEYS = Set.of(
            "hp_below", "hp_above", "still_seconds", "charge_seconds",     // 数值
            "angle", "target_state",                                        // 枚举
            "moving", "blocking", "charge_active", "parry",                 // 开关
            "stacks", "count");                                             // 引用

    private static final Set<String> NUMERIC = Set.of("hp_below", "hp_above", "still_seconds", "charge_seconds");
    private static final Set<String> FLAG = Set.of("moving", "blocking", "charge_active", "parry");
    private static final Set<String> REF = Set.of("stacks", "count");

    public static Condition number(String key, double value) {
        return new Condition(key, value, "", "", "");
    }

    public static Condition text(String key, String value) {
        return new Condition(key, 0.0, value, "", "");
    }

    public static Condition flag(String key, boolean value) {
        return new Condition(key, value ? 1.0 : 0.0, "", "", "");
    }

    public static Condition stacks(String stackId, double atLeast) {
        return new Condition("stacks", atLeast, "", stackId, "");
    }

    public static Condition count(String event, double atLeast) {
        return new Condition("count", atLeast, "", "", event);
    }

    /** 解析 {"key": value} 形态；失败返回 null 并把原因写进 errors。 */
    public static Condition parse(JsonObject json, List<String> errors) {
        List<String> known = new ArrayList<>();
        for (String key : json.keySet()) {
            if (!KEYS.contains(key)) {
                errors.add("unknown condition: " + key);
                continue;
            }
            known.add(key);
        }
        if (known.size() != 1) {
            errors.add("condition must have exactly one key, got " + json.keySet());
            return null;
        }
        String key = known.get(0);
        JsonElement value = json.get(key);
        if (NUMERIC.contains(key)) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                errors.add("condition " + key + " needs a number");
                return null;
            }
            return number(key, value.getAsDouble());
        }
        if (FLAG.contains(key)) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                errors.add("condition " + key + " needs true/false");
                return null;
            }
            return flag(key, value.getAsBoolean());
        }
        if (key.equals("angle") || key.equals("target_state")) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                errors.add("condition " + key + " needs a string");
                return null;
            }
            String text = value.getAsString();
            if (key.equals("angle") && !Mechanics.ANGLE_VALUES.contains(text)) {
                errors.add("condition angle must be one of " + Mechanics.ANGLE_VALUES + ", got " + text);
                return null;
            }
            return text(key, text);
        }
        // 引用型：{"id","at_least"} / {"on","at_least"}
        if (!value.isJsonObject()) {
            errors.add("condition " + key + " needs an object");
            return null;
        }
        JsonObject body = value.getAsJsonObject();
        String refKey = key.equals("stacks") ? "id" : "on";
        if (!body.has(refKey) || !body.has("at_least")) {
            errors.add("condition " + key + " needs {" + refKey + ", at_least}");
            return null;
        }
        for (String bodyKey : body.keySet()) {
            if (!bodyKey.equals(refKey) && !bodyKey.equals("at_least")) {
                errors.add("unknown condition field: " + bodyKey);
                return null;
            }
        }
        String ref = body.get(refKey).getAsString();
        double atLeast = body.get("at_least").getAsDouble();
        return key.equals("stacks") ? stacks(ref, atLeast) : count(ref, atLeast);
    }

    public boolean isStacks() {
        return key.equals("stacks");
    }

    public boolean isCount() {
        return key.equals("count");
    }
}
