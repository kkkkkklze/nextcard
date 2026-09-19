package com.klze.nextcard.core.card;

import com.klze.nextcard.core.load.LoadResult;
import com.klze.nextcard.core.tag.TagIndex;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 卡片注册表（只读）。跨卡校验在这里：duplicate id、{@code requires} 引用的标签必须已注册
 * （v1.1：requires 是标签谓词——拥有 ≥1 张带该标签的卡即满足，不再指向体系）、
 * 每张卡至少有一个非判断标签（否则没有任何池能投影出它，永远无法被抽到）。
 */
public record CardIndex(Map<ResourceLocation, CardDefinition> byId,
                        Set<ResourceLocation> registeredTags,
                        Set<ResourceLocation> markerTags) {

    public static final CardIndex EMPTY =
            new CardIndex(Map.of(), Set.of(), Set.of());

    public static CardIndex empty() {
        return EMPTY;
    }

    public static LoadResult<CardIndex> build(Collection<CardDefinition> cards, TagIndex tags) {
        List<String> errors = new ArrayList<>();
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
                if (!tags.contains(required)) {
                    errors.add(card.id() + ": requires must reference a registered tag, got " + required);
                }
            }
            if (card.tags().stream().allMatch(tags::isMarker)) {
                errors.add(card.id() + ": every card needs at least one non-judgment tag (otherwise no pool can hold it)");
            }
        }
        return new LoadResult<>(new CardIndex(Map.copyOf(map), Set.copyOf(tags.ids()), Set.copyOf(tags.markerIds())),
                errors);
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
}
