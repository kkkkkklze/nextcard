package com.klze.nextcard.core.tag;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Set;

/** 标签注册表（只读）。标签必须先注册，卡片才能引用（加载期硬校验，fail-fast）。 */
public record TagIndex(Map<ResourceLocation, TagDefinition> byId) {

    public static final TagIndex EMPTY = new TagIndex(Map.of());

    public boolean contains(ResourceLocation id) {
        return byId.containsKey(id);
    }

    public Set<ResourceLocation> ids() {
        return byId.keySet();
    }
}
