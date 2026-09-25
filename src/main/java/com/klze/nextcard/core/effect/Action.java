package com.klze.nextcard.core.effect;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.klze.nextcard.core.load.Strict;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 动作（触发器成立时发生了什么）。本类只做<b>结构校验</b>：类型是否注册、附带字段是否合法、
 * 必填字段在不在；具体语义（造成多少伤害、硬直几秒）由 M2 的执行器消费。
 *
 * <p>这样切分的理由：动作的数值细节属于实现，而"允许出现哪些动作、每个动作允许哪些字段"
 * 属于词表——词表必须先冻结，执行器才可以后补。未知动作类型在加载期即报错，绝不静默。</p>
 */
public record Action(String type, JsonObject body) {

    private static final Map<String, Set<String>> KEYS = actionKeys();
    private static final Map<String, Set<String>> REQUIRED = requiredKeys();

    private static Map<String, Set<String>> actionKeys() {
        Map<String, Set<String>> keys = new LinkedHashMap<>();
        keys.put("stacks", Set.of("id", "amount", "ignore_cap", "consume"));
        keys.put("damage", Set.of("basis", "coefficient", "radius", "radius_per_stack"));
        keys.put("knockback", Set.of("strength", "radius"));
        keys.put("stun", Set.of("seconds", "radius"));
        keys.put("launch", Set.of("landing_ratio"));
        keys.put("slow", Set.of("percent", "seconds", "radius"));
        keys.put("reflect", Set.of("ratio", "basis"));
        keys.put("extra_resolve", Set.of("count", "unbounded", "radius"));
        keys.put("copy_attack", Set.of("radius"));
        keys.put("crit", Set.of("guaranteed", "chance", "damage"));
        keys.put("lethal_immunity", Set.of("uses", "cooldown"));
        keys.put("force_parry", Set.of("seconds"));
        keys.put("ignore_armor", Set.of("ratio"));
        keys.put("interrupt", Set.of("seconds", "radius"));
        return Map.copyOf(keys);
    }

    private static Map<String, Set<String>> requiredKeys() {
        Map<String, Set<String>> required = new LinkedHashMap<>();
        required.put("stacks", Set.of("id"));
        required.put("damage", Set.of("basis", "coefficient"));
        required.put("knockback", Set.of("strength"));
        required.put("stun", Set.of("seconds"));
        required.put("launch", Set.of("landing_ratio"));
        required.put("slow", Set.of("percent", "seconds"));
        required.put("reflect", Set.of("ratio"));
        required.put("copy_attack", Set.of("radius"));
        required.put("force_parry", Set.of("seconds"));
        required.put("ignore_armor", Set.of("ratio"));
        required.put("interrupt", Set.of("seconds"));
        return Map.copyOf(required);
    }

    /** 已注册的动作类型。 */
    public static Set<String> types() {
        return KEYS.keySet();
    }

    /** 解析 {"动作名": {...}} 的单键对象；失败返回 null 并把原因写进 errors。 */
    public static Action parse(JsonElement element, List<String> errors) {
        if (!element.isJsonObject()) {
            errors.add("action must be an object");
            return null;
        }
        JsonObject outer = element.getAsJsonObject();
        List<String> names = new ArrayList<>(outer.keySet());
        if (names.size() != 1) {
            errors.add("action must have exactly one type key, got " + names);
            return null;
        }
        String type = names.get(0);
        Set<String> allowed = KEYS.get(type);
        if (allowed == null) {
            errors.add("unknown action type: " + type);
            return null;
        }
        JsonElement payload = outer.get(type);
        if (!payload.isJsonObject()) {
            errors.add("action " + type + " needs an object body");
            return null;
        }
        JsonObject body = payload.getAsJsonObject();
        errors.addAll(Strict.unknownKeys(body, allowed, "action " + type));
        for (String required : REQUIRED.getOrDefault(type, Set.of())) {
            if (!body.has(required)) {
                errors.add("action " + type + " needs field " + required);
                return null;
            }
        }
        return new Action(type, body);
    }

    /** 引用型动作指向的 id（叠层 id），供加载期跨卡校验。 */
    public String referencedStackId() {
        if (type.equals("stacks") && body.has("id")) {
            return body.get("id").getAsString();
        }
        return "";
    }

    public double number(String field, double fallback) {
        return body.has(field) ? body.get(field).getAsDouble() : fallback;
    }

    public boolean flag(String field, boolean fallback) {
        return body.has(field) ? body.get(field).getAsBoolean() : fallback;
    }

    public String text(String field, String fallback) {
        return body.has(field) ? body.get(field).getAsString() : fallback;
    }
}
