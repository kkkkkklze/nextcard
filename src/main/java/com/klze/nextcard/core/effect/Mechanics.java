package com.klze.nextcard.core.effect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 机制词表（首板卡表第一批：随盾反 / 蓄力两个三级池注册的语汇）。两类槽位，一个合成规则表。
 *
 * <p><b>槽位（slot）</b>是效果唯一的改写入口，分两种 kind：</p>
 * <ul>
 *   <li>{@link Kind#EVENT}——触发源（格挡成功、蓄力被打断……），只能被 {@code trigger} 子句监听；</li>
 *   <li>{@link Kind#PARAM}——可被改写的规则参数（窗口长度、蓄力速度、减伤上限……），
 *       只能被 {@code mechanic_modifier} 子句改写。</li>
 * </ul>
 *
 * <p><b>合成方式（{@link Combine}）由槽位自己声明，不由卡面决定</b>——这是把"延长项走加法还是乘法"
 * 这类问题从卡面收进引擎的关键：一张卡想改某个参数，只有该参数声明过的合并方式可用，
 * 卡面写不出第二种解释。上限类参数声明 {@link Combine#MAX}（取最宽松）或
 * {@link Combine#UNCAPPED}（解除上限），于是"上限 3→10→解除"这条件链不需要任何特判。</p>
 *
 * <p>槽位 id 的三种形态：{@code <事件名>}、{@code <参数名>}、{@code stack.<叠层 id>.<参数>}。
 * 前两种是引擎语汇（本类注册）；第三种里的叠层 id 由内容声明（{@code stacks} 子句），
 * 引擎只校验参数名合法、id 非空——"引用一个不存在的叠层"在加载期由
 * {@link EffectClauses#validateReferences} 跨卡校验拦下，绝不静默。</p>
 */
public final class Mechanics {

    /** 槽位类别：触发源 / 规则参数。 */
    public enum Kind {
        EVENT, PARAM
    }

    /**
     * 合成方式：同一个槽位被多张卡改写时的合并规则。
     *
     * <p>数值类槽位声明 ADD / MULTIPLY / MAX / DIVIDE 之一；上限类槽位用 MAX 表达
     * 「取最宽松」，用 {@link #UNCAPPED} 表达「解除上限」（布尔式，无值）；
     * 语义唯一的开关（如"能否主动取消蓄力"）用 {@link #SET}——两处 SET 值不同即加载期报错，
     * 矛盾必须显式解决而不是悄悄取一个。</p>
     */
    public enum Combine {
        /** 覆盖；多个来源值必须一致，否则加载期报错。 */
        SET,
        /** 加算（延长项：+30% 与 +50% 合成 +80%）。 */
        ADD,
        /** 乘算（倍率项：×3 与 ×2 合成 ×6；难度项也走这里，见 {@link #DIVIDE}）。 */
        MULTIPLY,
        /** 取最宽松（上限类：取最高值）。 */
        MAX,
        /** 除法项（难度：÷3 与 ÷2 合成 ÷6）。 */
        DIVIDE,
        /** 解除上限（布尔式，无值）。 */
        UNCAPPED
    }

    /** 参数取值形态（决定校验与之后的落点）。 */
    public enum ParamType {
        /** 纯数。 */
        NUMBER,
        /** 秒。 */
        SECONDS,
        /** 百分比（0.3 = 30%）。 */
        PERCENT,
        /** 开关（true/false）。 */
        FLAG,
        /** 枚举（取值由槽位注释给出）。 */
        ENUM
    }

    /** 一个槽位。 */
    public record Slot(String id, Kind kind, ParamType type, Combine combine) {
    }

    /** 叠层参数的允许名（{@code stack.<id>.<参数>} 的第三段）。 */
    public static final Set<String> STACK_PARAMS = Set.of("cap", "duration", "gain");

    /** 挡位枚举取值：正面 &lt; 正面+两侧 &lt; 全向（{@code block.angle} 用，取最宽松）。 */
    public static final List<String> ANGLE_VALUES = List.of("front", "front_side", "all");

    // 常量必须先于 SLOTS 声明：buildSlots() 在静态初始化时就要读它们（顺序错了会是 NPE）。
    /** 数值通道（附带数值用的全部名字）。 */
    public static final List<String> CHANNELS = List.of(
            "armor", "max_health", "max_health_scale", "move_speed", "attack_speed",
            "crit_chance", "crit_damage",
            "all_damage", "physical_damage", "melee_damage", "base_damage", "direction_bonus",
            "armor_pierce", "lifesteal", "resistance", "damage_reduction");

    /** 点值通道（卡面写 "+10 护甲值"）；其余通道是比值（卡面写 "+10%"）。 */
    public static final Set<String> FLAT_CHANNELS = Set.of("armor", "max_health", "base_damage");

    /** 乘区通道：卡面写"额外乘区"的独立相乘桶——桶内仍然加算，桶本身才与主桶相乘。 */
    public static final Set<String> SCALE_CHANNELS = Set.of("max_health_scale");

    /** 能直接落成原版 AttributeModifier 的通道；其余进自定义伤害管线（M2）。 */
    public static final Set<String> VANILLA_CHANNELS = Set.of("armor", "max_health", "move_speed", "attack_speed");

    private static final Map<String, Slot> SLOTS = buildSlots();

    private Mechanics() {
    }

    private static Map<String, Slot> buildSlots() {
        Map<String, Slot> slots = new LinkedHashMap<>();
        // —— 事件（触发源）：动作类，无合成 ——
        for (String event : List.of(
                "attack", "hit", "kill", "tick",
                "damage_taken", "damage_dealt",
                "block_success", "parry_success", "parry_fail",
                "charge_release", "charge_interrupt", "control_immune",
                "shield_down", "stand_still", "skill_cast")) {
            slots.put(event, new Slot(event, Kind.EVENT, ParamType.FLAG, Combine.SET));
        }
        // —— 参数：盾反的判定窗口（合成方式照池说明：延长项加法、难度项除法）——
        param(slots, "window.base", ParamType.SECONDS, Combine.SET);
        param(slots, "window.length", ParamType.PERCENT, Combine.ADD);
        param(slots, "window.scale", ParamType.PERCENT, Combine.MULTIPLY);
        param(slots, "window.difficulty", ParamType.PERCENT, Combine.DIVIDE);
        // —— 参数：格挡与奖励 ——
        param(slots, "block.angle", ParamType.ENUM, Combine.MAX);
        param(slots, "block.reduction", ParamType.PERCENT, Combine.ADD);
        param(slots, "block.reduction_scale", ParamType.PERCENT, Combine.MULTIPLY);
        param(slots, "block.reduction_cap", ParamType.PERCENT, Combine.MAX);
        param(slots, "parry.reward_cap", ParamType.PERCENT, Combine.MAX);
        // —— 参数：蓄力（上限取最高、加成加算；倍率有独立上限）——
        param(slots, "charge.bar_max", ParamType.SECONDS, Combine.MAX);
        param(slots, "charge.bar_bonus", ParamType.SECONDS, Combine.ADD);
        param(slots, "charge.rate", ParamType.PERCENT, Combine.ADD);
        param(slots, "charge.resolve", ParamType.PERCENT, Combine.ADD);
        param(slots, "charge.resolve_cap", ParamType.PERCENT, Combine.MAX);
        param(slots, "charge.damage_taken", ParamType.PERCENT, Combine.ADD);
        param(slots, "charge.control_duration", ParamType.PERCENT, Combine.ADD);
        param(slots, "charge.cancelable", ParamType.FLAG, Combine.SET);
        param(slots, "charge.interruptible", ParamType.FLAG, Combine.SET);
        // —— 参数：伤害与数值通道（附带数值的落点）——
        // 通道一律加算：值恒为卡面写的增量（+15% 就是 0.15）。"额外乘区"不是另一种合成方式，
        // 而是"这个通道的增量自成一个相乘桶"（见 SCALE_CHANNELS）——合成方式属于槽位，
        // 桶的归属属于通道语义，两者不混。
        for (String channel : CHANNELS) {
            ParamType type = FLAT_CHANNELS.contains(channel) ? ParamType.NUMBER : ParamType.PERCENT;
            param(slots, "channel." + channel, type, Combine.ADD);
        }
        return Map.copyOf(slots);
    }

    private static void param(Map<String, Slot> slots, String id, ParamType type, Combine combine) {
        slots.put(id, new Slot(id, Kind.PARAM, type, combine));
    }

    /**
     * 已注册的机制与各自的参数名。参数含义由各机制自己的求解器负责
     * （{@code parry} → {@link WindowMath}，{@code charge} → {@link ChargeTable}）；
     * 布尔式参数用 1 / 0 表达。
     */
    public static final Map<String, Set<String>> MECHANIC_PARAMS = Map.of(
            "parry", Set.of("base_window", "ordinary_reduction", "reduction_cap", "cancelable"),
            "charge", Set.of("bar_max", "cancelable"));

    /** 按 id 查槽位；未注册返回 {@code null}（调用方负责报错文案）。 */
    public static Slot slot(String id) {
        Slot direct = SLOTS.get(id);
        if (direct != null) {
            return direct;
        }
        if (!id.startsWith("stack.")) {
            return null;
        }
        int dot = id.lastIndexOf('.');
        if (dot <= "stack.".length() || dot == id.length() - 1) {
            return null;
        }
        String param = id.substring(dot + 1);
        if (!STACK_PARAMS.contains(param)) {
            return null;
        }
        // 叠层参数继承通用合成：上限/时长取最宽松，产出加算。
        Combine combine = param.equals("gain") ? Combine.ADD : Combine.MAX;
        ParamType type = param.equals("duration") ? ParamType.SECONDS : ParamType.NUMBER;
        return new Slot(id, Kind.PARAM, type, combine);
    }

    public static Set<String> ids() {
        return SLOTS.keySet();
    }

    /**
     * 槽位的中性基准值：加算/覆盖/取最宽的基准是 0，乘除的基准是 1。
     *
     * <p>上限类槽位（取最宽）真正有意义的基准来自内容声明（如叠层的 {@code cap}），
     * 由 {@link MechanicProfile#stackCap} 这类"声明值 + 改写"的方法负责，不用中性值。</p>
     */
    public static double neutral(Slot slot) {
        return switch (slot.combine()) {
            case MULTIPLY, DIVIDE -> 1.0;
            default -> 0.0;
        };
    }

    public static double neutral(String id) {
        Slot slot = slot(id);
        return slot == null ? 0.0 : neutral(slot);
    }

    /** 参数槽位所属的机制 id（跨卡校验用：改写一个没人声明过的机制 = 加载错误）。 */
    public static String ownerMechanic(String slotId) {
        if (slotId.startsWith("charge.")) {
            return "charge";
        }
        if (slotId.startsWith("window.") || slotId.startsWith("block.") || slotId.startsWith("parry.")) {
            return "parry";
        }
        return null;
    }

    /** 上限类槽位（合成方式为取最宽松）——只有它允许被"解除上限"。 */
    public static boolean isCap(Slot slot) {
        return slot.combine() == Combine.MAX;
    }

    /** 折叠结果：合成值 + 是否被解除上限（"不再有上限"类卡留下的事实，而不是一个数字）。 */
    public record Folded(double value, boolean uncapped, String text) {

        public Folded(double value, boolean uncapped) {
            this(value, uncapped, "");
        }

        public boolean hasText() {
            return !text.isEmpty();
        }
    }

    /**
     * 按槽位声明的合成方式折叠一组来源值。
     *
     * <p>{@code base} 是机制声明的基准值（没有基准时传槽位的中性值：加算 0、乘算 1）。
     * {@link Combine#SET} 的多个值由跨卡校验保证一致，这里取第一个；
     * {@link Combine#UNCAPPED} 忽略数值只置位。</p>
     */
    public static Folded fold(Slot slot, double base, List<Double> values) {
        List<Double> usable = new ArrayList<>(values);
        if (slot.combine() == Combine.UNCAPPED) {
            return new Folded(base, !usable.isEmpty());
        }
        if (usable.isEmpty()) {
            return new Folded(base, false);
        }
        double result = switch (slot.combine()) {
            case SET -> usable.get(0);
            case MULTIPLY -> {
                double product = base;
                for (double value : usable) {
                    product *= value;
                }
                yield product;
            }
            case DIVIDE -> {
                double quotient = base;
                for (double value : usable) {
                    quotient /= value;
                }
                yield quotient;
            }
            case MAX -> {
                double best = base;
                for (double value : usable) {
                    best = Math.max(best, value);
                }
                yield best;
            }
            case ADD -> {
                double sum = base;
                for (double value : usable) {
                    sum += value;
                }
                yield sum;
            }
            case UNCAPPED -> base;
        };
        return new Folded(result, false);
    }

    /** 枚举类槽位（如 {@code block.angle}）取最宽松档；越靠后越宽松。 */
    public static String widestAngle(List<String> values) {
        String best = ANGLE_VALUES.get(0);
        for (String value : values) {
            if (ANGLE_VALUES.indexOf(value) > ANGLE_VALUES.indexOf(best)) {
                best = value;
            }
        }
        return best;
    }

    /** 枚举类槽位的折叠：同样按槽位声明的"取最宽松"，没有值时返回空串。 */
    public static Folded foldText(Slot slot, List<String> values) {
        if (values.isEmpty()) {
            return new Folded(0.0, false, "");
        }
        if (slot.combine() == Combine.MAX && slot.id().equals("block.angle")) {
            return new Folded(0.0, false, widestAngle(values));
        }
        return new Folded(0.0, false, values.get(0));
    }
}
