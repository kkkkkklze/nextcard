package com.klze.nextcard.core.effect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.klze.nextcard.core.card.CardDefinition;
import com.klze.nextcard.core.load.LoadResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 效果子句词表。注册表按 {@code type} 字符串分派。
 *
 * <p>M0 时这里刻意为空（"空词表 + 非空 effects 数组 = 加载错误，绝不静默"）；首批注册的是
 * 盾反与蓄力两个三级池需要的四类子句——{@code stacks}（层数/资源）、{@code trigger}
 * （事件触发与一次性兑现窗口）、{@code mechanic}（机制声明与授予）、
 * {@code mechanic_modifier}（槽位改写与数值通道）。合并方式不在这里，也不在卡面：
 * 它由槽位声明（{@link Mechanics.Combine}）——词表只回答"能写什么"，
 * "写出来的东西怎么合并"只有一个答案。</p>
 *
 * <p>{@link #validateReferences} 负责跨卡校验：改写一个没人声明过的机制、引用一个不存在的
 * 叠层、或对同一个"语义唯一"的槽位给出两个不同值，都在加载期报错并定位到卡 id。</p>
 */
public final class EffectClauses {

    /** type → 解析器。 */
    private static final Map<String, Parser> REGISTRY = Map.of(
            "stacks", StackClause::parse,
            "trigger", TriggerClause::parse,
            "mechanic", MechanicClause::parse,
            "mechanic_modifier", ModifierClause::parse);

    @FunctionalInterface
    public interface Parser {
        EffectClause parse(JsonObject body, List<String> errors);
    }

    private EffectClauses() {
    }

    /** 已注册的子句类型（文档与测试用）。 */
    public static Set<String> types() {
        return REGISTRY.keySet();
    }

    /** 解析卡片 JSON 的 effects 数组；缺省（键不存在）= 空表，合法。 */
    public static LoadResult<List<EffectClause>> parseArray(JsonArray array) {
        List<String> errors = new ArrayList<>();
        List<EffectClause> clauses = new ArrayList<>();
        if (array != null) {
            for (JsonElement element : array) {
                if (!(element instanceof JsonObject obj) || !obj.has("type")) {
                    errors.add("effect clause must be an object with a \"type\" field");
                    continue;
                }
                String type = obj.get("type").getAsString();
                Parser parser = REGISTRY.get(type);
                if (parser == null) {
                    errors.add("unknown effect type: " + type);
                    continue;
                }
                EffectClause clause = parser.parse(obj, errors);
                if (clause != null) {
                    clauses.add(clause);
                }
            }
        }
        return new LoadResult<>(List.copyOf(clauses), errors);
    }

    /**
     * 跨卡校验（在 {@code ContentReader} 组装完所有卡之后调用）。三类硬错误：
     * 引用未声明的机制、引用未声明的叠层、语义唯一槽位出现两个不同值。
     */
    public static List<String> validateReferences(Collection<CardDefinition> cards) {
        List<String> errors = new ArrayList<>();
        Set<String> declaredStacks = new TreeSet<>();
        Map<String, StackClause.Scope> stackScopes = new TreeMap<>();
        Set<String> declaredMechanics = new TreeSet<>();
        for (CardDefinition card : cards) {
            for (EffectClause clause : card.effects()) {
                if (clause instanceof StackClause stack) {
                    declaredStacks.add(stack.id());
                    StackClause.Scope previous = stackScopes.put(stack.id(), stack.scope());
                    if (previous != null && previous != stack.scope()) {
                        errors.add(card.id() + ": stack " + stack.id() + " is declared with conflicting scopes");
                    }
                } else if (clause instanceof MechanicClause mechanic) {
                    declaredMechanics.add(mechanic.id());
                }
            }
        }
        Map<String, String> exclusiveValues = new LinkedHashMap<>();
        Map<String, String> exclusiveOwner = new LinkedHashMap<>();
        for (CardDefinition card : cards) {
            for (EffectClause clause : card.effects()) {
                if (clause instanceof ModifierClause modifier) {
                    checkModifier(card, modifier, declaredStacks, declaredMechanics, errors);
                    checkExclusive(card, modifier, exclusiveValues, exclusiveOwner, errors);
                } else if (clause instanceof StackClause stack) {
                    for (ModifierClause perStack : stack.perStack()) {
                        checkModifier(card, perStack, declaredStacks, declaredMechanics, errors);
                    }
                    for (Action action : stack.onMax()) {
                        checkAction(card, action, declaredStacks, errors);
                    }
                    for (StackClause.Gain gain : stack.gain()) {
                        for (Condition condition : gain.when()) {
                            checkCondition(card, condition, declaredStacks, errors);
                        }
                    }
                } else if (clause instanceof TriggerClause trigger) {
                    for (Action action : trigger.actions()) {
                        checkAction(card, action, declaredStacks, errors);
                    }
                    for (Condition condition : trigger.when()) {
                        checkCondition(card, condition, declaredStacks, errors);
                    }
                }
            }
        }
        return List.copyOf(errors);
    }

    private static void checkModifier(CardDefinition card, ModifierClause modifier, Set<String> stacks,
                                      Set<String> mechanics, List<String> errors) {
        String target = modifier.target();
        if (!target.startsWith("stack.")) {
            String owner = Mechanics.ownerMechanic(target);
            if (owner != null && !mechanics.contains(owner)) {
                errors.add(card.id() + ": " + target + " modifies mechanic " + owner
                        + ", but no card declares it");
            }
            return;
        }
        int dot = target.lastIndexOf('.');
        String stackId = target.substring("stack.".length(), Math.max("stack.".length(), dot));
        if (!stacks.contains(stackId)) {
            errors.add(card.id() + ": " + target + " references undeclared stack " + stackId);
        }
        if (modifier.isPerLayer()) {
            checkLayerCounter(card, modifier.sourceCounter(), stacks, errors);
        }
    }

    private static void checkLayerCounter(CardDefinition card, String counter, Set<String> stacks,
                                          List<String> errors) {
        if (!stacks.contains(counter)) {
            errors.add(card.id() + ": per-layer source references undeclared stack " + counter);
        }
    }

    private static void checkExclusive(CardDefinition card, ModifierClause modifier,
                                       Map<String, String> values, Map<String, String> owners,
                                       List<String> errors) {
        Mechanics.Slot slot = Mechanics.slot(modifier.target());
        if (slot == null || slot.combine() != Mechanics.Combine.SET
                || modifier.isPerLayer() || modifier.uncapped()) {
            return;
        }
        String value = String.valueOf(modifier.value());
        String previous = values.putIfAbsent(modifier.target(), value);
        if (previous == null) {
            owners.put(modifier.target(), card.id().toString());
        } else if (!previous.equals(value)) {
            errors.add(card.id() + ": " + modifier.target() + " is already set to " + previous
                    + " by " + owners.get(modifier.target()) + "; conflicting values must be resolved in-content");
        }
    }

    private static void checkAction(CardDefinition card, Action action, Set<String> stacks, List<String> errors) {
        String stackId = action.referencedStackId();
        if (!stackId.isEmpty() && !stacks.contains(stackId)) {
            errors.add(card.id() + ": action references undeclared stack " + stackId);
        }
    }

    private static void checkCondition(CardDefinition card, Condition condition, Set<String> stacks,
                                       List<String> errors) {
        if (condition.isStacks() && !stacks.contains(condition.stackId())) {
            errors.add(card.id() + ": condition references undeclared stack " + condition.stackId());
        }
    }
}
