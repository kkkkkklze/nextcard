package com.klze.nextcard.core.effect;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 重算对账（v1.0 §4.1-6）。没有「应用效果」与「撤销效果」两条路，只有「重算」一条：
 * 抽到卡生效、移除卡回退（并退次数）、体系移除清零计数器，全部由
 * 「重算快照 → 与旧快照 diff → 差量落点」自动成立。
 *
 * <p>两层差量：{@link #diff} 说"哪些卡与体系进出"（记账与提示用），
 * {@link #slotDiff} 说"哪个槽位的值变了、从多少变到多少"（MC 侧据此撤旧挂新）。</p>
 */
public final class Reconciler {

    private Reconciler() {
    }

    public enum Kind {
        CARD_GAINED, CARD_LOST, SYSTEM_OPENED, SYSTEM_CLOSED
    }

    /** 语义差量：一条变更 = 一个种类 + 一个 id，不再拼字符串。 */
    public record Change(Kind kind, ResourceLocation id) {
    }

    public static List<Change> diff(EffectSnapshot before, EffectSnapshot after) {
        List<Change> changes = new ArrayList<>();
        for (ResourceLocation id : after.cards()) {
            if (!before.cards().contains(id)) {
                changes.add(new Change(Kind.CARD_GAINED, id));
            }
        }
        for (ResourceLocation id : before.cards()) {
            if (!after.cards().contains(id)) {
                changes.add(new Change(Kind.CARD_LOST, id));
            }
        }
        for (ResourceLocation system : after.systems()) {
            if (!before.systems().contains(system)) {
                changes.add(new Change(Kind.SYSTEM_OPENED, system));
            }
        }
        for (ResourceLocation system : before.systems()) {
            if (!after.systems().contains(system)) {
                changes.add(new Change(Kind.SYSTEM_CLOSED, system));
            }
        }
        return List.copyOf(changes);
    }

    /**
     * 槽位级差量。MC 侧要撤一条属性修饰，必须能说清"撤的是哪个槽位、原来是多少"——
     * 只有卡进出的字符串差量做不到这件事（两张卡改同一槽位时会互相抵消，看不出净变化）。
     *
     * <p>槽位 id 同时是稳定 UUID 的派生材料（{@code UUID.nameUUIDFromBytes(持有者 + "/" + slot)}），
     * 所以重算天然幂等：同一组卡折叠两次得到同一个 UUID，不会出现叠加堆积。</p>
     */
    public record SlotChange(String slot, double from, double to,
                             boolean fromUncapped, boolean toUncapped,
                             String fromText, String toText) {

        public boolean numericChanged() {
            return Double.compare(from, to) != 0;
        }

        public boolean textChanged() {
            return !fromText.equals(toText);
        }

        public boolean uncappedChanged() {
            return fromUncapped != toUncapped;
        }
    }

    public static List<SlotChange> slotDiff(MechanicProfile before, MechanicProfile after) {
        TreeSet<String> ids = new TreeSet<>();
        ids.addAll(before.slotIds());
        ids.addAll(after.slotIds());
        List<SlotChange> changes = new ArrayList<>();
        for (String id : ids) {
            Mechanics.Folded from = before.slot(id);
            Mechanics.Folded to = after.slot(id);
            boolean moved = Double.compare(from.value(), to.value()) != 0
                    || from.uncapped() != to.uncapped()
                    || !from.text().equals(to.text());
            if (moved) {
                changes.add(new SlotChange(id, from.value(), to.value(),
                        from.uncapped(), to.uncapped(), from.text(), to.text()));
            }
        }
        return List.copyOf(changes);
    }
}
