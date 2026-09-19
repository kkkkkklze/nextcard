package com.klze.nextcard.core.draw;

import com.klze.nextcard.core.card.CardDefinition;
import com.klze.nextcard.core.card.CardIndex;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 已拥有卡的标签权重表（W_t = 带标签 t 的拥有卡张数；多标签卡对每个标签各计 1）。
 * <b>判断标签不计入</b>（v1.1 §5.5：不参与概率，只参与判断）；
 * 孤儿卡（数据包删卡后）不产生权重（Q22 默认：失效卡灰显）。
 */
public final class TagWeights {

    private final Map<ResourceLocation, Integer> byTag;
    private final int total;

    private TagWeights(Map<ResourceLocation, Integer> byTag, int total) {
        this.byTag = byTag;
        this.total = total;
    }

    public static TagWeights of(CardIndex index, Set<ResourceLocation> owned) {
        Map<ResourceLocation, Integer> byTag = new HashMap<>();
        int total = 0;
        for (ResourceLocation id : owned) {
            CardDefinition card = index.byId().get(id);
            if (card == null) {
                continue; // 孤儿卡
            }
            for (ResourceLocation tag : card.tags()) {
                if (index.markerTags().contains(tag)) {
                    continue; // 判断标签不参与概率
                }
                byTag.merge(tag, 1, Integer::sum);
                total++;
            }
        }
        return new TagWeights(byTag, total);
    }

    public int weightOf(ResourceLocation tag) {
        return byTag.getOrDefault(tag, 0);
    }

    public int total() {
        return total;
    }

    /** 确定性顺序（按 toString 排序，无 JVM 盐）。 */
    public List<ResourceLocation> tagOrder() {
        List<ResourceLocation> tags = new ArrayList<>(byTag.keySet());
        tags.sort(java.util.Comparator.comparing(ResourceLocation::toString));
        return tags;
    }
}
