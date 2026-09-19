package com.klze.nextcard.core.tag;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 标签注册表（只读）。标签必须先注册，卡片才能引用（加载期硬校验，fail-fast）。 */
public record TagIndex(Map<ResourceLocation, TagDefinition> byId) {

    public static final TagIndex EMPTY = new TagIndex(Map.of());

    public boolean contains(ResourceLocation id) {
        return byId.containsKey(id);
    }

    /** 判断标签（judgment_only）：不参与概率，只做 requires 谓词。 */
    public boolean isMarker(ResourceLocation id) {
        TagDefinition tag = byId.get(id);
        return tag != null && tag.judgmentOnly();
    }

    public Set<ResourceLocation> ids() {
        return byId.keySet();
    }

    public Set<ResourceLocation> markerIds() {
        return byId.values().stream()
                .filter(TagDefinition::judgmentOnly)
                .map(TagDefinition::id)
                .collect(Collectors.toUnmodifiableSet());
    }
}
