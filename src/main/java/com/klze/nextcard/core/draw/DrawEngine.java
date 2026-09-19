package com.klze.nextcard.core.draw;

import com.klze.nextcard.core.card.CardDefinition;
import com.klze.nextcard.core.card.CardIndex;
import com.klze.nextcard.core.pool.PoolIndex;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.ToIntFunction;

/**
 * 抽卡引擎（v1.0 §5.3）。<b>纯函数</b>：输入 (CardIndex, PoolIndex, profile, 等级权重, 已拥有集, RNG)，
 * 输出 (候选, 决策日志)；不碰方块、玩家、NBT、网络，蒙特卡洛模拟器直接喂它（门 G3）。
 *
 * <p>三阶段采样：① 定等级（日程权重只在有候选的等级上归一，无候选等级剔除）；
 * ② 定池子（标签份额 S = cap×ΣW/(ΣW+W0) 渐近；标签之间按 W_t/ΣW 分配；无候选标签的权重作废、
 * 损失份额归随机支；空池剔除——第三批裁定 4，取代降级链）；③ 定卡（池内候选均匀）。
 * 槽位过滤器无候选时回退为无过滤（首槽必 A 自 A 入场起生效的机制形态，第四批裁定 1）。</p>
 *
 * <p>个人可抽池 = 全卡 − 已拥有 − 前置体系未满足（第一批裁定 3「选定踢池」+ C2 语义）——
 * 纯函数过滤，无每玩家结构。引擎对卡类零特判：A/B/C 只是过滤器取值。</p>
 */
public final class DrawEngine {

    private DrawEngine() {
    }

    public static DrawResult draw(CardIndex index, PoolIndex pools, DrawProfile profile,
                                  Map<Integer, Integer> tierWeights, Set<ResourceLocation> owned,
                                  Random random) {
        List<String> log = new ArrayList<>();

        // 个人可抽池 = 全卡 − 已拥有 − requires 未满足（v1.1：requires 是标签谓词，
        // 拥有 ≥1 张带该标签的卡即满足——判断标签与普通标签同一谓词，无特例）。
        Set<ResourceLocation> ownedTagSet = new HashSet<>();
        for (ResourceLocation id : owned) {
            CardDefinition card = index.byId().get(id);
            if (card != null) {
                ownedTagSet.addAll(card.tags());
            }
        }
        TreeSet<ResourceLocation> candidates = new TreeSet<>();
        for (CardDefinition card : index.byId().values()) {
            if (owned.contains(card.id())) {
                continue;
            }
            if (!ownedTagSet.containsAll(card.requires())) {
                continue;
            }
            candidates.add(card.id());
        }
        if (candidates.isEmpty()) {
            return DrawResult.noCards();
        }

        if (profile.fixedCards().isPresent()) {
            List<ResourceLocation> offers = new ArrayList<>();
            for (ResourceLocation fixed : profile.fixedCards().get()) {
                if (candidates.contains(fixed)) {
                    offers.add(fixed);
                } else {
                    log.add("fixed " + fixed + " skipped: not a candidate");
                }
            }
            if (offers.isEmpty()) {
                return DrawResult.noCards();
            }
            return new DrawResult(DrawResult.Status.OK, List.copyOf(offers), log);
        }

        List<ResourceLocation> offers = new ArrayList<>();
        Set<ResourceLocation> offered = new HashSet<>();
        TagWeights weights = TagWeights.of(index, owned);
        int slotIndex = 0;
        for (DrawProfile.Slot slot : profile.slots()) {
            TreeSet<ResourceLocation> space = new TreeSet<>(candidates);
            space.removeAll(offered);
            if (space.isEmpty()) {
                log.add("slot" + slotIndex + " skip: no candidates");
                slotIndex++;
                continue;
            }

            // 阶段 1：定等级——日程是更高法则：只在「日程给了正权重的等级」内抽，
            // 槽位过滤（首槽必 A）只允许在日程允许的等级内收紧，不能把日程外的高等级拉进来
            // （门 G3 断言 T5 不早于第 9 抽曾抓住这个顺序错误）。权重全零/缺失 → 有候选等级间均匀。
            Map<Integer, List<ResourceLocation>> byTier = new HashMap<>();
            for (ResourceLocation id : space) {
                byTier.computeIfAbsent(index.byId().get(id).tier(), k -> new ArrayList<>()).add(id);
            }
            List<Integer> allowedTiers = new ArrayList<>();
            for (Map.Entry<Integer, List<ResourceLocation>> tierEntry : byTier.entrySet()) {
                if (tierWeights.getOrDefault(tierEntry.getKey(), 0) > 0) {
                    allowedTiers.add(tierEntry.getKey());
                }
            }
            if (allowedTiers.isEmpty()) {
                allowedTiers.addAll(byTier.keySet());
            }
            allowedTiers.sort(Integer::compareTo);
            boolean classRestricted = false;
            if (slot.filter().cardClass().isPresent()) {
                CardClass want = slot.filter().cardClass().get();
                List<Integer> filteredTiers = new ArrayList<>();
                for (Integer tierKey : allowedTiers) {
                    boolean hasWant = false;
                    for (ResourceLocation id : byTier.get(tierKey)) {
                        if (index.byId().get(id).cardClass() == want) {
                            hasWant = true;
                            break;
                        }
                    }
                    if (hasWant) {
                        filteredTiers.add(tierKey);
                    }
                }
                if (!filteredTiers.isEmpty()) {
                    allowedTiers = filteredTiers;
                    classRestricted = true;
                } else {
                    log.add("slot" + slotIndex + " filter-fallback: no " + want + " candidate in allowed tiers");
                }
            }
            int tier = weightedPick(allowedTiers, t -> tierWeights.getOrDefault(t, 0), random);
            List<ResourceLocation> tierSpace = byTier.get(tier);
            if (classRestricted) {
                CardClass want = slot.filter().cardClass().orElseThrow();
                List<ResourceLocation> restricted = new ArrayList<>();
                for (ResourceLocation id : tierSpace) {
                    if (index.byId().get(id).cardClass() == want) {
                        restricted.add(id);
                    }
                }
                tierSpace = restricted;
            }

            // 阶段 2：定池子——标签份额渐近分配；无候选标签的权重作废，损失归随机支。
            double share = WeightModel.tagShare(weights.total());
            ResourceLocation pickedTag = null;
            double roll = random.nextDouble();
            double cumulative = 0.0;
            for (ResourceLocation tag : weights.tagOrder()) {
                List<ResourceLocation> pool = pools.cardsIn(tier, tag);
                if (pool.isEmpty() || pool.stream().noneMatch(tierSpace::contains)) {
                    continue;
                }
                cumulative += share * weights.weightOf(tag) / Math.max(1, weights.total());
                if (roll < cumulative) {
                    pickedTag = tag;
                    break;
                }
            }

            // 阶段 3：定卡——标签支在 (等级，标签) 池内均匀，随机支在该等级候选内均匀。
            ResourceLocation card;
            if (pickedTag != null) {
                List<ResourceLocation> pool = new ArrayList<>(pools.cardsIn(tier, pickedTag));
                pool.retainAll(tierSpace);
                card = pool.get(random.nextInt(pool.size()));
                log.add("slot" + slotIndex + " tier=" + tier + " branch=tag tag=" + pickedTag
                        + " S=" + String.format("%.3f", share) + " card=" + card);
            } else {
                card = tierSpace.get(random.nextInt(tierSpace.size()));
                log.add("slot" + slotIndex + " tier=" + tier + " branch=random S=" + String.format("%.3f", share)
                        + " card=" + card);
            }
            offers.add(card);
            offered.add(card);
            slotIndex++;
        }

        if (offers.isEmpty()) {
            return DrawResult.noCards();
        }
        return new DrawResult(DrawResult.Status.OK, List.copyOf(offers), log);
    }

    /** 整数权重挑选；全零/缺失 → 均匀（「权重在非空选项上归一」的统一形态）。 */
    private static <T> T weightedPick(List<T> items, ToIntFunction<T> weight, Random random) {
        long total = 0;
        for (T item : items) {
            total += Math.max(0, weight.applyAsInt(item));
        }
        if (total <= 0) {
            return items.get(random.nextInt(items.size()));
        }
        long roll = random.nextLong(total);
        for (T item : items) {
            long w = Math.max(0, weight.applyAsInt(item));
            if (roll < w) {
                return item;
            }
            roll -= w;
        }
        return items.get(items.size() - 1);
    }
}
