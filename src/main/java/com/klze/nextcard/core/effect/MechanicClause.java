package com.klze.nextcard.core.effect;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 机制声明子句：<b>声明并授予</b>一个机制，附带它的基础参数。
 *
 * <pre>
 * { "type": "mechanic", "id": "parry",  "base_window": 0.4, "ordinary_reduction": 0.2 }
 * { "type": "mechanic", "id": "charge", "bar_max": 3.0 }
 * </pre>
 *
 * <p>为什么"声明"与"授予"是同一件事：卡表里给出机制本体的卡（招式卡、入场券）同时就是
 * 获得该能力的卡——"【获取X能力】蓄力条上限 3 秒"既是能力本身也是获取途径。
 * 只改规则不提供本体的卡写 {@code mechanic_modifier}，它引用的机制 id 在加载期被校验必须
 * 有人声明过（{@link EffectClauses#validateReferences}），于是"初始无效果"是结构成立的事实，
 * 而不是靠卡面文字保证的约定。</p>
 *
 * <p>参数名由 {@link Mechanics#MECHANIC_PARAMS} 按机制注册；参数与槽位的关系由各机制的
 * 求解器负责（{@link WindowMath} / {@link ChargeTable}）。</p>
 */
public record MechanicClause(String id, Map<String, Double> params) implements EffectClause {

    public MechanicClause {
        params = Map.copyOf(params);
    }

    /** 已注册的机制 id。 */
    public static Set<String> ids() {
        return Mechanics.MECHANIC_PARAMS.keySet();
    }

    public static MechanicClause parse(JsonObject body, List<String> errors) {
        if (!body.has("id") || !body.get("id").isJsonPrimitive() || !body.getAsJsonPrimitive("id").isString()) {
            errors.add("mechanic needs a string id");
            return null;
        }
        String id = body.get("id").getAsString();
        Set<String> allowed = Mechanics.MECHANIC_PARAMS.get(id);
        if (allowed == null) {
            errors.add("unknown mechanic: " + id);
            return null;
        }
        Map<String, Double> params = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : body.entrySet()) {
            String key = entry.getKey();
            if (key.equals("type") || key.equals("id")) {
                continue;
            }
            if (!allowed.contains(key)) {
                errors.add("mechanic " + id + ": unknown parameter " + key);
                continue;
            }
            JsonElement value = entry.getValue();
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                errors.add("mechanic " + id + ": parameter " + key + " must be a number");
                continue;
            }
            params.put(key, value.getAsDouble());
        }
        return new MechanicClause(id, params);
    }

    public double param(String name, double fallback) {
        return params.getOrDefault(name, fallback);
    }

    public static MechanicClause of(String id, Map<String, Double> params) {
        return new MechanicClause(id, params);
    }
}
