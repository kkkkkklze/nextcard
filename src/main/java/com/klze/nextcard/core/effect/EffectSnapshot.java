package com.klze.nextcard.core.effect;

import com.klze.nextcard.core.card.CardIndex;
import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/**
 * 效果快照（v1.0 §4.1-6）：拥有卡集是唯一事实源，快照是它派生的生效状态。
 * M0 快照只携带卡集与开启的体系；M2 在此之上叠加属性/事件/周期的具体落点。
 */
public record EffectSnapshot(Set<ResourceLocation> cards, Set<ResourceLocation> systems) {

    public static EffectSnapshot compute(CardIndex index, Set<ResourceLocation> ownedIds) {
        return new EffectSnapshot(Set.copyOf(ownedIds), index.systemsOf(ownedIds));
    }
}
