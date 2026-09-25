package com.klze.nextcard.core.player;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 玩家卡账（M1 的唯一玩家状态，纯逻辑、可无头测试）。
 *
 * <p>三条从兄弟工程踩坑里带过来的口径，全都写在这一个类里：</p>
 * <ol>
 *   <li><b>只存 id，不存实例</b>。持有的是"哪些卡"，效果一律由 {@code EffectHost} 现算——
 *       所以卡表热重载、内容包换版本都不需要迁移存档。</li>
 *   <li><b>认不出的 id 原样写回</b>。整合包作者升降版本时，卡可能暂时不存在；把它从存档里
 *       抹掉等于偷走玩家的卡，而且下一次重算再也长不回来。这里保留并单独记账上报。</li>
 *   <li><b>顺序确定</b>。持有集合按授予顺序而非哈希序输出，否则"第 N 次抽卡"这类账本与
 *       同步包内容会随 JVM 抖动。</li>
 * </ol>
 */
public final class CardLedger {

    /**
     * 存档 schema 版本。与 {@code ModNetwork.PROTOCOL_VERSION} 是<b>两个数</b>：
     * 网络格式变了不必然代表存档格式变了，反之亦然，混成一个会让"改协议"顺手废档。
     */
    public static final int SCHEMA_VERSION = 1;

    private final Set<ResourceLocation> owned = new LinkedHashSet<>();
    /**
     * 存档里<b>连解析都失败</b>的条目，原样保留。它们不进 {@link #owned}（那里只能是合法 id），
     * 但必须照样写回去——否则"未知 id 不许掉"这条只对"名字合法但内容里没有"生效，
     * 而真正会掉档的恰恰是后者以外的那一类：别的 mod 改过命名空间、或手写坏了文件名。
     */
    private final List<String> unparsed = new ArrayList<>();
    private final List<String> unknownOnLoad = new ArrayList<>();
    private final Map<String, Integer> counters = new TreeMap<>();
    private int drawCount;

    /** 授予一张卡。返回是否真的改变了账本（重复授予不产生第二次效果）。 */
    public boolean grant(ResourceLocation cardId) {
        return owned.add(cardId);
    }

    /** 移除一张卡（整合包侧的"退卡"，同时应当退一次抽卡机会——那由调用方记）。 */
    public boolean revoke(ResourceLocation cardId) {
        return owned.remove(cardId);
    }

    public boolean owns(ResourceLocation cardId) {
        return owned.contains(cardId);
    }

    public Set<ResourceLocation> owned() {
        return Set.copyOf(owned);
    }

    public int size() {
        return owned.size() + unparsed.size();
    }

    public int drawCount() {
        return drawCount;
    }

    public void recordDraw() {
        drawCount++;
    }

    /** 回退一次抽卡机会（退卡时调用）。不会低于 0。 */
    public void refundDraw() {
        drawCount = Math.max(0, drawCount - 1);
    }

    public int counter(String systemId) {
        return counters.getOrDefault(systemId, 0);
    }

    public void addCounter(String systemId, int amount) {
        counters.merge(systemId, amount, Integer::sum);
    }

    /** 体系被移除时清零它的计数器（重算路径，不留第二份真相）。 */
    public void clearCounter(String systemId) {
        counters.remove(systemId);
    }

    /** 序列化成纯文本形态（MC 侧写 CompoundTag 时直接用它，避免两处各拼一遍 key）。 */
    public List<String> ownedAsText() {
        List<String> names = new ArrayList<>();
        for (ResourceLocation id : owned) {
            names.add(id.toString());
        }
        names.addAll(unparsed);
        return List.copyOf(names);
    }

    public Map<String, Integer> countersAsText() {
        return Map.copyOf(counters);
    }

    /** 读档时认不出的卡 id（内容包版本不一致的上报口，不是错误）。 */
    public List<String> unknownOnLoad() {
        return List.copyOf(unknownOnLoad);
    }

    /**
     * 从存档文本重建。
     *
     * @param knownIds 当前内容里存在的卡；{@code null} 表示"还没有内容"（reload 之前读档），
     *                 此时一律按保留处理，绝不因为查不到就丢卡
     */
    public static CardLedger fromText(List<String> ownedIds, int drawCount, Map<String, Integer> counters,
                                      Set<ResourceLocation> knownIds) {
        CardLedger ledger = new CardLedger();
        ledger.drawCount = Math.max(0, drawCount);
        for (String raw : ownedIds) {
            ResourceLocation parsed = ResourceLocation.tryParse(raw);
            if (parsed == null) {
                ledger.unknownOnLoad.add(raw);
                ledger.unparsed.add(raw);
                continue;
            }
            if (knownIds != null && !knownIds.contains(parsed)) {
                ledger.unknownOnLoad.add(raw);
            }
            ledger.owned.add(parsed);
        }
        ledger.counters.putAll(counters);
        return ledger;
    }
}
