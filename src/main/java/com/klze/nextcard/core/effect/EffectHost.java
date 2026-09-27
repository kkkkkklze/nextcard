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
import java.util.TreeMap;

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

    private final java.util.function.Supplier<CardIndex> index;
    private final CounterStore counters = new CounterStore();
    private final Map<String, EffectSnapshot> applied = new HashMap<>();
    private final Map<String, MechanicProfile> folded = new HashMap<>();
    private final Set<String> dirty = new LinkedHashSet<>();

    public EffectHost(java.util.function.Supplier<CardIndex> index) {
        this.index = index;
    }

    public CounterStore counters() {
        return counters;
    }

    /** 授予一张卡：只标脏，不立刻生效。 */
    public void grant(String holder, ResourceLocation cardId) {
        if (!index.get().byId().containsKey(cardId)) {
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

    /**
     * 用外部真源（玩家卡账）覆盖这里的持有集，只在确实不同时标脏。
     *
     * <p>存在的理由：卡账才是持有关系的家，宿主只负责"算生效状态"。没有这个入口，两边各持一份
     * 持有集就会分叉——那是"两份真相"最常见的一种形状。</p>
     */
    public void syncOwned(String holder, Set<ResourceLocation> ids) {
        Set<ResourceLocation> current = owned(holder);
        if (current.equals(ids)) {
            return;
        }
        current.clear();
        for (ResourceLocation id : ids) {
            if (!index.get().byId().containsKey(id)) {
                throw new IllegalArgumentException("unknown card: " + id);
            }
            current.add(id);
        }
        dirty.add(holder);
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
     * 这个持有者当前持有的全部触发子句（带来源卡 id）——{@link Triggers} 唯一的入口。
     *
     * <p>每次现读卡表而不是缓存一份：内容热重载换表之后，触发器必须跟着换，
     * 否则"改了卡面但冷却还在老路上"会有两份真相。读的是 {@link #owned}，
     * 也就是刚被玩家卡账同步过的那一份。</p>
     */
    public List<Triggers.Bound> triggers(String holder) {
        List<Triggers.Bound> bound = new ArrayList<>();
        CardIndex index = this.index.get();
        for (ResourceLocation id : owned.getOrDefault(holder, Set.of())) {
            CardDefinition card = index.byId().get(id);
            if (card == null) {
                continue; // 卡被内容换版删掉了：加载期已报，这里不该炸运行期
            }
            for (EffectClause clause : card.effects()) {
                if (clause instanceof TriggerClause trigger) {
                    bound.add(new Triggers.Bound(id, trigger));
                }
            }
        }
        return bound;
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
            EffectSnapshot after = EffectSnapshot.compute(index.get(), ids);
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

    /**
     * 标记某个持有者需要重算。<b>层数变了必须调它</b>：每层映射（{@code per_stack}）折进快照的
     * 值是层数的函数，只标"卡集变了"会让快照停在旧层数上——玩家看到的就是"层数在涨，减伤没动"。
     */
    public void markDirty(String holder) {
        dirty.add(holder);
    }

    /**
     * 当前卡表里<b>所有</b>叠层声明（id → 声明）。执行器据此知道上限与时长该是多少。
     *
     * <p>为什么按整张卡表算、不按持有者算：同一个 id 在卡表里就是<em>同一种资源</em>（"壁障"不会
     * 因为两张卡都提到它就变成两本账），而多张卡各自复述这套资源正是卡表的正常写法。合并规则与
     * 槽位 {@code stack.<id>.cap} / {@code .duration} 的 {@code MAX} 合成同出一条，不另定一套。
     * 只读 {@code cap} / {@code duration}：谁该<em>产出</em>这条资源由触发子句自己说。</p>
     */
    public Map<String, StackClause> declaredStacks() {
        Map<String, StackClause> declared = new TreeMap<>();
        for (CardDefinition card : index.get().byId().values()) {
            for (EffectClause clause : card.effects()) {
                if (clause instanceof StackClause stack) {
                    declared.merge(stack.id(), stack, EffectHost::loosest);
                }
            }
        }
        return declared;
    }

    private static StackClause loosest(StackClause left, StackClause right) {
        double cap = Math.max(left.cap(), right.cap());
        double duration = Math.max(left.duration(), right.duration());
        return new StackClause(left.id(), left.scope(), cap, duration, left.gain(), left.perStack(),
                left.onMax());
    }

    private MechanicProfile foldModifiers(String holder, Set<ResourceLocation> ids) {
        List<ModifierClause> modifiers = new ArrayList<>();
        for (ResourceLocation id : ids) {
            CardDefinition card = index.get().byId().get(id);
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
