package com.klze.nextcard.core.effect;

import com.klze.nextcard.core.card.CardDefinition;
import com.klze.nextcard.core.card.CardIndex;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生效宿主：拥有卡集 → 重算 → 差量 → 才通知（v1.0 §4.1-6 的执行形状）。
 *
 * <p>三条约束是这个类存在的全部理由，缺一条就会长出第二真相：</p>
 * <ol>
 *   <li><b>只有重算这一条路</b>。没有 {@code apply()} 也没有 {@code undo()}——抽到卡是重算，
 *       移除卡也是重算（回退与"退一次抽卡机会"因此自动成立，不需要写反向代码）。</li>
 *   <li><b>脏标记 + 每 tick 一次 flush</b>。一次施法里可能连着grant 三张卡，逐张落属性会把
 *       同一个槽位改三遍；攒到 flush 一次算完，才是玩家看到的那个值。</li>
 *   <li><b>先重算完，再通知</b>。监听者（网络包、HUD、属性重挂）读到的必须是已经算完的状态，
 *       所以通知阶段一律在全部持有者重算结束后才跑——见 {@link #flush}。</li>
 * </ol>
 */
public final class EffectHost {

    /** 差量落地通知：MC 侧实现它来挂属性、发同步包、刷 HUD。 */
    public interface Listener {
        void changed(String holder, EffectSnapshot snapshot, List<Reconciler.Change> cards,
                     List<Reconciler.SlotChange> slots);
    }

    private final CardIndex index;
    private final CounterStore counters = new CounterStore();
    private final Map<String, EffectSnapshot> applied = new HashMap<>();
    private final Map<String, MechanicProfile> folded = new HashMap<>();
    private final Set<String> dirty = new LinkedHashSet<>();

    public EffectHost(CardIndex index) {
        this.index = index;
    }

    public CounterStore counters() {
        return counters;
    }

    /** 授予一张卡：只标脏，不立刻生效。 */
    public void grant(String holder, ResourceLocation cardId) {
        if (!index.byId().containsKey(cardId)) {
            throw new IllegalArgumentException("unknown card: " + cardId);
        }
        owned(holder).add(cardId);
        dirty.add(holder);
    }

    /** 移除一张卡（整合包侧的"退卡"）：同样只是标脏，回退由重算给出。 */
    public boolean revoke(String holder, ResourceLocation cardId) {
        boolean removed = owned(holder).remove(cardId);
        if (removed) {
            dirty.add(holder);
        }
        return removed;
    }

    public Set<ResourceLocation> owned(String holder) {
        return owned.computeIfAbsent(holder, key -> new LinkedHashSet<>());
    }

    private final Map<String, Set<ResourceLocation>> owned = new HashMap<>();

    public boolean isDirty(String holder) {
        return dirty.contains(holder);
    }

    /** 上一轮 flush 之后折好的机制快照；未 flush 过则为空表（不返回半成品）。 */
    public MechanicProfile profile(String holder) {
        return folded.getOrDefault(holder, new MechanicProfile(Map.of()));
    }

    /**
     * 把所有脏持有者重算一遍，然后统一通知。返回本次处理的持有者数。
     *
     * <p>两阶段是分开的：第一阶段只算（改内部状态、算差量），第二阶段才回调监听者。
     * 合成一次做的话，第一个监听者会看到第二个持有者还没算完的中间态。</p>
     */
    public int flush(List<Listener> listeners) {
        if (dirty.isEmpty()) {
            return 0;
        }
        List<Pending> pending = new ArrayList<>();
        for (String holder : List.copyOf(dirty)) {
            dirty.remove(holder);
            Set<ResourceLocation> ids = Set.copyOf(owned(holder));
            EffectSnapshot before = applied.getOrDefault(holder, new EffectSnapshot(Set.of(), Set.of()));
            EffectSnapshot after = EffectSnapshot.compute(index, ids);
            MechanicProfile afterProfile = foldModifiers(holder, ids);
            pending.add(new Pending(holder, after, afterProfile,
                    Reconciler.diff(before, after), Reconciler.slotDiff(profile(holder), afterProfile)));
            applied.put(holder, after);
            folded.put(holder, afterProfile);
        }
        for (Pending item : pending) {
            for (Listener listener : listeners) {
                listener.changed(item.holder, item.snapshot, item.cards, item.slots);
            }
        }
        return pending.size();
    }

    /** 单持有者便捷入口（调试命令与测试用）。 */
    public int flush(Listener listener) {
        return flush(List.of(listener));
    }

    private MechanicProfile foldModifiers(String holder, Set<ResourceLocation> ids) {
        List<ModifierClause> modifiers = new ArrayList<>();
        for (ResourceLocation id : ids) {
            CardDefinition card = index.byId().get(id);
            if (card == null) {
                continue;
            }
            for (EffectClause clause : card.effects()) {
                if (clause instanceof ModifierClause modifier) {
                    modifiers.add(modifier);
                }
            }
        }
        return MechanicProfile.fold(modifiers, stackId ->
                (int) counters.amount(new CounterStore.Key(holder, stackId)));
    }

    private record Pending(String holder, EffectSnapshot snapshot, MechanicProfile profile,
                           List<Reconciler.Change> cards, List<Reconciler.SlotChange> slots) {
    }
}
