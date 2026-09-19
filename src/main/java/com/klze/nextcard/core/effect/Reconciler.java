package com.klze.nextcard.core.effect;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 重算对账（v1.0 §4.1-6）。没有「应用效果」与「撤销效果」两条路，只有「重算」一条：
 * 抽到卡生效、移除卡回退（并退次数）、体系移除清零计数器，全部由
 * 「重算快照 → 与旧快照 diff → 差量落点」自动成立。
 */
public final class Reconciler {

    private Reconciler() {
    }

    /** 语义差量（M2 起由 MC 侧消费成具体的属性/事件/tick 变更；M0 只给出可断言的语义结果）。 */
    public static List<String> diff(EffectSnapshot before, EffectSnapshot after) {
        List<String> changes = new ArrayList<>();
        for (ResourceLocation id : after.cards()) {
            if (!before.cards().contains(id)) {
                changes.add("+" + id);
            }
        }
        for (ResourceLocation id : before.cards()) {
            if (!after.cards().contains(id)) {
                changes.add("-" + id);
            }
        }
        for (ResourceLocation system : after.systems()) {
            if (!before.systems().contains(system)) {
                changes.add("+system:" + system);
            }
        }
        for (ResourceLocation system : before.systems()) {
            if (!after.systems().contains(system)) {
                changes.add("-system:" + system);
            }
        }
        return List.copyOf(changes);
    }
}
