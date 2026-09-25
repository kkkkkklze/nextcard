package com.klze.nextcard.core.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToIntFunction;

/**
 * 折叠后的机制快照：把一组修饰子句按槽位声明的合成方式折成一张表。
 *
 * <p>这是"重算对账"里可无头验证的那一半：拥有卡集 → 全部修饰子句 → 每个槽位一个值
 * （或一个"已解除上限"的事实）。M2 再把这张表落成属性修饰 / 事件表 / tick 计数。
 * 因为折叠是纯函数，卡表的数值链可以在不启游戏的情况下先跑起来（见 logicTest）。</p>
 */
public record MechanicProfile(Map<String, Mechanics.Folded> slots) {

    public MechanicProfile {
        slots = Map.copyOf(slots);
    }

    /** 每层来源的层数由 {@code layers} 提供（未拥有的资源返回 0）。 */
    public static MechanicProfile fold(List<ModifierClause> modifiers, ToIntFunction<String> layers) {
        Map<String, List<Double>> grouped = new TreeMap<>();
        Map<String, List<String>> texts = new TreeMap<>();
        Map<String, Boolean> unlocked = new TreeMap<>();
        for (ModifierClause modifier : modifiers) {
            if (modifier.uncapped()) {
                unlocked.put(modifier.target(), true);
                continue;
            }
            if (modifier.isText()) {
                texts.computeIfAbsent(modifier.target(), key -> new ArrayList<>()).add(modifier.text());
                continue;
            }
            grouped.computeIfAbsent(modifier.target(), key -> new ArrayList<>())
                    .add(modifier.valueFor(layers));
        }
        Map<String, Mechanics.Folded> folded = new TreeMap<>();
        for (Map.Entry<String, List<Double>> entry : grouped.entrySet()) {
            Mechanics.Slot slot = Mechanics.slot(entry.getKey());
            if (slot == null) {
                continue; // 加载期已拦下未注册槽位，这里只是防御
            }
            folded.put(entry.getKey(), Mechanics.fold(slot, Mechanics.neutral(slot), entry.getValue()));
        }
        for (Map.Entry<String, List<String>> entry : texts.entrySet()) {
            Mechanics.Slot slot = Mechanics.slot(entry.getKey());
            if (slot != null) {
                folded.put(entry.getKey(), Mechanics.foldText(slot, entry.getValue()));
            }
        }
        for (String target : unlocked.keySet()) {
            Mechanics.Slot slot = Mechanics.slot(target);
            if (slot != null) {
                folded.put(target, new Mechanics.Folded(Mechanics.neutral(slot), true));
            }
        }
        return new MechanicProfile(folded);
    }

    public Mechanics.Folded slot(String id) {
        return slots.getOrDefault(id, new Mechanics.Folded(Mechanics.neutral(id), false));
    }

    public double number(String id, double fallback) {
        Mechanics.Folded folded = slots.get(id);
        return folded == null ? fallback : folded.value();
    }

    /** 枚举类槽位的折叠结果（如格挡角度档 {@code all}）；没有则返回空串。 */
    public String text(String id) {
        Mechanics.Folded folded = slots.get(id);
        return folded == null ? "" : folded.text();
    }

    /** 该槽位是否被某张卡"解除上限"（而不是被改成一个更大的数字）。 */
    public boolean uncapped(String id) {
        Mechanics.Folded folded = slots.get(id);
        return folded != null && folded.uncapped();
    }

    /** 某个叠层资源的实际上限：声明的基准值再按 {@code stack.<id>.cap} 折叠。 */
    public double stackCap(String stackId, double declared) {
        Mechanics.Slot slot = Mechanics.slot("stack." + stackId + ".cap");
        if (slot == null) {
            return declared;
        }
        Mechanics.Folded folded = slots.get(slot.id());
        if (folded == null) {
            return declared;
        }
        return folded.uncapped() ? StackClause.UNBOUNDED : Math.max(declared, folded.value());
    }

    /** 全部数值通道（附带数值的落点）。 */
    public Map<String, Double> channels() {
        Map<String, Double> channels = new TreeMap<>();
        for (Map.Entry<String, Mechanics.Folded> entry : slots.entrySet()) {
            if (entry.getKey().startsWith("channel.")) {
                channels.put(entry.getKey().substring("channel.".length()), entry.getValue().value());
            }
        }
        return Map.copyOf(channels);
    }

    public double channel(String name) {
        return number("channel." + name, 0.0);
    }

    /** 只落成原版 AttributeModifier 的那几个通道（其余进自定义伤害管线，M2）。 */
    public Map<String, Double> vanillaChannels() {
        Map<String, Double> vanilla = new TreeMap<>();
        for (Map.Entry<String, Double> entry : channels().entrySet()) {
            if (Mechanics.VANILLA_CHANNELS.contains(entry.getKey())) {
                vanilla.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(vanilla);
    }

    /** 快照里出现过的槽位 id（调试与测试用）。 */
    public Set<String> slotIds() {
        return slots.keySet();
    }
}
