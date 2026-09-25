package com.klze.nextcard.core.effect;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.klze.nextcard.core.load.Strict;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 事件触发子句：注册一个事件源上的动作，可带一次性兑现窗口，或绑定一个蓄力点。
 *
 * <pre>
 * { "type": "trigger",
 *   "on": "parry_success",                              // 必须是已注册事件（Mechanics.slot）
 *   "window": { "seconds": 3.0, "uses": 1 },             // 缺省 = 瞬时执行；uses 缺省 = 不限次数
 *   "when": [ { "hp_below": 0.3 } ],                     // 全部成立才触发
 *   "actions": [ { "crit": { "guaranteed": true } } ] }
 *
 * { "type": "trigger",
 *   "on": "charge_release", "at": 2.0, "inherits": true,  // 蓄力点（仅 charge_release 可绑）
 *   "actions": [ { "stun": { "seconds": 1.5 } } ] }
 * </pre>
 *
 * <p>"T 秒内的下一次 X 获得 Y"（窗口制造者的全部写法）就是本子句：窗口 + {@code uses}。
 * 窗口内再被改写（翻倍、无上限）由 {@code actions} 里对应的动作自行声明，
 * 不由窗口字段表达——窗口只负责"什么时候有效"。</p>
 *
 * <p>{@code at} 是蓄力点的秒数（见 {@link ChargeTable}）：只对 {@code charge_release} 有意义，
 * 绑在其它事件上是加载错误。</p>
 */
public record TriggerClause(String on, double atSeconds, boolean inherits,
                            double windowSeconds, int uses,
                            List<Condition> when, List<Action> actions) implements EffectClause {

    /** {@code uses} 的缺省：窗口内不限次数。 */
    public static final int UNLIMITED_USES = -1;
    /** {@code at} 的缺省：不绑定蓄力点。 */
    public static final double NO_CHARGE_POINT = -1.0;

    private static final Set<String> KEYS = Set.of("type", "on", "at", "inherits", "window", "when", "actions");
    private static final Set<String> WINDOW_KEYS = Set.of("seconds", "uses");

    public TriggerClause {
        when = List.copyOf(when);
        actions = List.copyOf(actions);
    }

    public static TriggerClause parse(JsonObject body, List<String> errors) {
        errors.addAll(Strict.unknownKeys(body, KEYS, "trigger"));
        if (!body.has("on") || !body.get("on").isJsonPrimitive() || !body.getAsJsonPrimitive("on").isString()) {
            errors.add("trigger needs a string on");
            return null;
        }
        String on = body.get("on").getAsString();
        Mechanics.Slot slot = Mechanics.slot(on);
        if (slot == null || slot.kind() != Mechanics.Kind.EVENT) {
            errors.add("unknown event: " + on);
            return null;
        }
        double atSeconds = NO_CHARGE_POINT;
        if (body.has("at")) {
            if (!on.equals("charge_release")) {
                errors.add("trigger at is only valid on charge_release, not " + on);
                return null;
            }
            atSeconds = body.get("at").getAsDouble();
            if (atSeconds <= 0) {
                errors.add("trigger at must be positive seconds");
                return null;
            }
        }
        boolean inherits = body.has("inherits") && body.get("inherits").getAsBoolean();
        if (inherits && atSeconds == NO_CHARGE_POINT) {
            errors.add("trigger inherits needs a charge point (at)");
            return null;
        }
        double windowSeconds = 0.0;
        int uses = UNLIMITED_USES;
        if (body.has("window")) {
            JsonElement window = body.get("window");
            if (!window.isJsonObject()) {
                errors.add("trigger " + on + " window must be an object");
                return null;
            }
            JsonObject windowBody = window.getAsJsonObject();
            errors.addAll(Strict.unknownKeys(windowBody, WINDOW_KEYS, "trigger window"));
            if (!windowBody.has("seconds")) {
                errors.add("trigger " + on + " window needs seconds");
                return null;
            }
            windowSeconds = windowBody.get("seconds").getAsDouble();
            if (windowSeconds <= 0) {
                errors.add("trigger " + on + " window seconds must be positive");
                return null;
            }
            if (windowBody.has("uses")) {
                uses = windowBody.get("uses").getAsInt();
                if (uses == 0 || uses < UNLIMITED_USES) {
                    errors.add("trigger " + on + " window uses must be positive or -1 (unlimited)");
                    return null;
                }
            }
        }
        List<Condition> conditions = new ArrayList<>();
        if (!StackClause.parseConditions(body, "when", conditions, errors)) {
            return null;
        }
        if (!body.has("actions") || !body.get("actions").isJsonArray()) {
            errors.add("trigger " + on + " needs a non-empty actions array");
            return null;
        }
        List<Action> actions = new ArrayList<>();
        for (JsonElement element : body.getAsJsonArray("actions")) {
            Action action = Action.parse(element, errors);
            if (action != null) {
                actions.add(action);
            }
        }
        if (actions.isEmpty()) {
            errors.add("trigger " + on + " needs at least one valid action");
            return null;
        }
        return new TriggerClause(on, atSeconds, inherits, windowSeconds, uses, conditions, actions);
    }

    public boolean hasWindow() {
        return windowSeconds > 0;
    }

    public boolean unlimitedUses() {
        return uses == UNLIMITED_USES;
    }
}
