package com.klze.nextcard.core.effect;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 判定条件的<b>叶子</b>（触发与改写的共用语汇）。四种形态共用一个 record，不给每个条件造一个类型：
 * 数值型（{@code hp_below: 0.3}）、枚举型（{@code angle: "front"}）、开关型
 * （{@code charge_active: true}）、引用型（{@code stacks: {"id": "...", "at_least": 3}}）。
 *
 * <p>条件只描述"什么时候成立"，不含任何数值计算；判定发生在运行期，加载期只做形态校验。
 * "或 / 若则 / 每三次一次"这类写法不在这里，在 {@link Combinators}——叶子再多也表达不了一句
 * "满足任意一条即可"，所以 {@code when} 数组的元素类型是 {@link Predicate}（树），不是本类。</p>
 *
 * <p>{@link #test(Facts)} 读的是 {@link Facts}（已经换算好的量），不是实体：于是每一条判据
 * 都能在无头环境里被证明，而不必靠人开游戏去站到怪背后。</p>
 */
public record Condition(String key, double number, String text, String stackId, String event) implements Predicate {

    /** 已注册的条件键（{@link Predicates#leafNames()} 是它的唯一对外读法）。 */
    public static final Set<String> KEYS = Set.of(
            "hp_below", "hp_above", "still_seconds", "charge_seconds",     // 数值（自己）
            "target_hp_below", "distance_within", "light_below", "noise_below", "back_sector",
            "angle", "target_state", "target_kind",                        // 枚举
            "moving", "blocking", "charge_active", "parry", "fatal",       // 开关
            "appeared_outside_view",
            "stacks", "count");                                            // 引用

    /** 目标状态取值（《00》"未察觉"第②条：正被其他单位吸引，或处于控制 / 失明状态）。 */
    public static final Set<String> TARGET_STATES = Set.of("controlled", "attracted_elsewhere", "blind");

    private static final Set<String> NUMERIC = Set.of("hp_below", "hp_above", "still_seconds", "charge_seconds",
            "target_hp_below", "distance_within", "light_below", "noise_below", "back_sector");
    private static final Set<String> FLAG = Set.of("moving", "blocking", "charge_active", "parry", "fatal",
            "appeared_outside_view");
    private static final Set<String> TEXT = Set.of("angle", "target_state", "target_kind");
    private static final Set<String> REF = Set.of("stacks", "count");

    /** 正面半角与"正面+两侧"半角：由《00》背后 120° 的定义对称推出来，不是拍的。 */
    public static final double FRONT_HALF_ANGLE = 60.0;
    public static final double FRONT_SIDE_HALF_ANGLE = 120.0;

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
        if (TEXT.contains(key)) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                errors.add("condition " + key + " needs a string");
                return null;
            }
            String text = value.getAsString();
            if (key.equals("angle") && !Mechanics.ANGLE_VALUES.contains(text)) {
                errors.add("condition angle must be one of " + Mechanics.ANGLE_VALUES + ", got " + text);
                return null;
            }
            if (key.equals("target_state") && !TARGET_STATES.contains(text)) {
                errors.add("condition target_state must be one of " + TARGET_STATES + ", got " + text);
                return null;
            }
            if (key.equals("target_kind") && !Facts.TARGET_KINDS.contains(text)) {
                errors.add("condition target_kind must be one of " + Facts.TARGET_KINDS + ", got " + text);
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

    /** 引用型叶子涉及的叠层 id（跨卡校验用；非引用型返回空）。 */
    public String referencedStack() {
        return isStacks() ? stackId : "";
    }

    /**
     * 枚举取值的中文名。归因是给"这张卡为什么没触发"看的，判据一律按英文键比较。
     * 名字取自《00》原文（"被其他单位吸引"、"控制 / 失明"）。
     */
    private static final Map<String, String> LABELS = Map.of(
            "controlled", "被控制", "attracted_elsewhere", "被其他单位吸引", "blind", "失明",
            "normal", "普通敌人", "elite", "精英", "boss", "首领", "player", "玩家",
            "front", "正面", "front_side", "正面+两侧", "all", "全向");

    private static String label(String value) {
        return LABELS.getOrDefault(value, value);
    }

    @Override
    public Verdict test(Facts facts) {
        return switch (key) {
            case "hp_below" -> compare(facts.attackerHpRatio() < number,
                    "生命 " + percent(facts.attackerHpRatio()) + (facts.attackerHpRatio() < number ? " 低于 " : " 不低于 ")
                            + percent(number));
            case "hp_above" -> compare(facts.attackerHpRatio() > number,
                    "生命 " + percent(facts.attackerHpRatio()) + " 高于 " + percent(number));
            case "target_hp_below" -> compare(facts.targetHpRatio() < number,
                    "目标生命 " + percent(facts.targetHpRatio()) + " 低于 " + percent(number));
            case "still_seconds" -> compare(!facts.flag("moving") && facts.stillSeconds() >= number,
                    (facts.flag("moving") ? "在动" : "静止 " + facts.stillSeconds() + " 秒") + "，需 " + number + " 秒不动");
            case "charge_seconds" -> compare(facts.chargeSeconds() >= number,
                    "已蓄 " + facts.chargeSeconds() + " 秒，需 " + number + " 秒");
            case "distance_within" -> compare(facts.distance() <= number,
                    "距离 " + facts.distance() + " 格，需 ≤ " + number + " 格");
            case "light_below" -> compare(facts.light() < number,
                    "光照 " + facts.light() + "，需 < " + number);
            case "noise_below" -> compare(facts.noise() < number,
                    "发声 " + facts.noise() + "，需 < " + number);
            // 《00》：背后 120° = 以背面中线为轴、两侧各 60°。卡面写多少度就用多少度（默认档位由内容给）。
            case "back_sector" -> compare(facts.angleOffBack() <= number,
                    "离背面中线 " + degrees(facts.angleOffBack()) + "°，需 ≤ " + degrees(number) + "°");
            case "angle" -> compare(angleHolds(facts), "入射角档 " + label(text) + "，实际离正面 "
                    + degrees(facts.angleOffFront()) + "°");
            case "target_state" -> compare(facts.flag("target_" + text), "目标状态 " + label(text));
            case "target_kind" -> compare(facts.targetKind().equals(text),
                    "目标类别 " + label(facts.targetKind()) + "，需 " + label(text));
            case "stacks" -> compare(facts.layers(stackId) >= (int) number,
                    "「" + stackId + "」" + facts.layers(stackId) + " 层，需 " + (int) number + " 层");
            case "count" -> compare(facts.count(event) >= (int) number,
                    "「" + event + "」本局 " + facts.count(event) + " 次，需 " + (int) number + " 次");
            default -> {
                boolean actual = flagValue(facts);
                yield compare(actual == wantsFlag(),
                        switchName() + (actual ? " 成立" : " 未成立") + "（卡面要求"
                                + (wantsFlag() ? "成立" : "不成立") + "）");
            }
        };
    }

    /** 开关型：卡面写 {@code true} 就是"要求它成立"，写 {@code false} 就是"要求它没发生"。 */
    private boolean flagValue(Facts facts) {
        return switch (key) {
            case "moving" -> facts.flag("moving");
            case "blocking" -> facts.flag("blocking");
            case "charge_active" -> facts.flag("charging");
            case "parry" -> facts.flag("parried");
            case "fatal" -> facts.flag("fatal");
            case "appeared_outside_view" -> facts.flag("appeared_from_outside_view");
            default -> false;
        };
    }

    private boolean wantsFlag() {
        return number >= 0.5;
    }

    private String switchName() {
        return switch (key) {
            case "moving" -> "在移动";
            case "blocking" -> "正在格挡";
            case "charge_active" -> "蓄力中";
            case "parry" -> "刚刚弹反成功";
            case "fatal" -> "这一发致命";
            case "appeared_outside_view" -> "刚从它的视野外出现";
            default -> key;
        };
    }

    private boolean angleHolds(Facts facts) {
        return switch (text) {
            case "front" -> facts.angleOffFront() <= FRONT_HALF_ANGLE;
            case "front_side" -> facts.angleOffFront() <= FRONT_SIDE_HALF_ANGLE;
            default -> true;
        };
    }

    private static Verdict compare(boolean holds, String reason) {
        return new Verdict(holds, reason);
    }

    private static String percent(double ratio) {
        return (Math.round(ratio * 1000.0) / 10.0) + "%";
    }

    private static String degrees(double value) {
        return String.valueOf(Math.round(value * 10.0) / 10.0);
    }

    @Override
    public String describe() {
        if (isStacks()) {
            return "「" + stackId + "」≥ " + (int) number + " 层";
        }
        if (isCount()) {
            return "「" + event + "」≥ " + (int) number + " 次";
        }
        if (!text.isEmpty()) {
            return key + "=" + label(text);
        }
        if (FLAG.contains(key)) {
            return (wantsFlag() ? "" : "非") + switchName();
        }
        return key + " " + number;
    }
}
