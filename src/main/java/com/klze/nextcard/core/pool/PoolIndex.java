package com.klze.nextcard.core.pool;

import com.klze.nextcard.core.card.CardDefinition;
import com.klze.nextcard.core.card.CardIndex;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 池子索引（v1.0 §4.1-3）：池 = (等级，标签) 网格，由卡片注册表<b>投影</b>而来。
 * 没有任何「注册一个池子」的代码路径——铁律 2 的结构体现；空池剔除（第三批裁定 4：
 * 空池不加入抽取），全部列表按 id 排序（确定性，datagen 迭代顺序带 JVM 盐的教训）。
 *
 * <p>这里是全局投影；玩家视角的候选过滤（已拥有踢出、requires 前置）在 {@code DrawEngine}
 * 输入侧完成，同样是纯函数，不引入每玩家结构。</p>
 */
public record PoolIndex(Map<Integer, Map<ResourceLocation, List<ResourceLocation>>> tiers) {

    public static final PoolIndex EMPTY = new PoolIndex(Map.of());

    public static PoolIndex of(CardIndex index) {
        Map<Integer, Map<ResourceLocation, Set<ResourceLocation>>> acc = new TreeMap<>();
        for (CardDefinition card : index.byId().values()) {
            for (ResourceLocation tag : card.tags()) {
                acc.computeIfAbsent(card.tier(), k -> new TreeMap<>())
                        .computeIfAbsent(tag, k -> new TreeSet<>())
                        .add(card.id());
            }
        }
        Map<Integer, Map<ResourceLocation, List<ResourceLocation>>> frozen = new TreeMap<>();
        acc.forEach((tier, byTag) -> {
            Map<ResourceLocation, List<ResourceLocation>> frozenTags = new TreeMap<>();
            byTag.forEach((tag, ids) -> frozenTags.put(tag, List.copyOf(ids)));
            frozen.put(tier, Map.copyOf(frozenTags));
        });
        return new PoolIndex(Map.copyOf(frozen));
    }

    public boolean isEmpty() {
        return tiers.isEmpty();
    }

    public Set<Integer> availableTiers() {
        return tiers.keySet();
    }

    /** (等级，标签) 池内的卡；池不存在 = 空表（空池剔除后的统一观感）。 */
    public List<ResourceLocation> cardsIn(int tier, ResourceLocation tag) {
        Map<ResourceLocation, List<ResourceLocation>> byTag = tiers.get(tier);
        return byTag == null ? List.of() : byTag.getOrDefault(tag, List.of());
    }

    /** 等级内全部卡（跨标签去重，id 有序）。 */
    public List<ResourceLocation> allInTier(int tier) {
        Map<ResourceLocation, List<ResourceLocation>> byTag = tiers.get(tier);
        if (byTag == null) {
            return List.of();
        }
        Set<ResourceLocation> all = new TreeSet<>();
        byTag.values().forEach(all::addAll);
        return List.copyOf(all);
    }
}
