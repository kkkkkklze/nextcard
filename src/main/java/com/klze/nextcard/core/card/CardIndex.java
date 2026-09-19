package com.klze.nextcard.core.card;

import com.klze.nextcard.core.load.LoadResult;
import com.klze.nextcard.core.tag.TagIndex;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 卡片注册表（只读）。跨卡校验在这里：duplicate id、{@code requires} 引用的体系必须由某张 A 卡声明。
 */
public record CardIndex(Map<ResourceLocation, CardDefinition> byId) {

    public static final CardIndex EMPTY = new CardIndex(Map.of());

    public static CardIndex empty() {
        return EMPTY;
    }

    public static LoadResult<CardIndex> build(Collection<CardDefinition> cards, TagIndex tags) {
        List<String> errors = new java.util.ArrayList<>();
        Map<ResourceLocation, CardDefinition> map = new TreeMap<>();
        for (CardDefinition card : cards) {
            errors.addAll(card.validate(tags));
            if (map.putIfAbsent(card.id(), card) != null) {
                errors.add(card.id() + ": duplicate card id");
            }
        }
        Set<ResourceLocation> systems = new HashSet<>();
        for (CardDefinition card : map.values()) {
            card.system().ifPresent(systems::add);
        }
        for (CardDefinition card : map.values()) {
            for (ResourceLocation required : card.requires()) {
                if (!systems.contains(required)) {
                    errors.add(card.id() + ": requires unknown system " + required);
                }
            }
        }
        return new LoadResult<>(new CardIndex(Map.copyOf(map)), errors);
    }

    /** 已拥有卡集开启的体系集合（体系存在 ⇔ 定义它的 A 卡被拥有——§5.4，无体系注册表）。 */
    public Set<ResourceLocation> systemsOf(Collection<ResourceLocation> ownedIds) {
        Set<ResourceLocation> systems = new HashSet<>();
        for (ResourceLocation id : ownedIds) {
            CardDefinition card = byId.get(id);
            if (card != null) {
                card.system().ifPresent(systems::add);
            }
        }
        return systems;
    }

    /** C2 默认语义：前置体系未拥有 ⇒ 该卡不进个人可抽池（效果因体系不存在自然无效——双保险同一机制）。 */
    public boolean satisfiesRequires(CardDefinition card, Set<ResourceLocation> ownedIds, Set<ResourceLocation> systemsOwned) {
        return systemsOwned.containsAll(card.requires());
    }
}
