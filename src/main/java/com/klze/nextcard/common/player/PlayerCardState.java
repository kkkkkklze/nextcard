package com.klze.nextcard.common.player;

import com.klze.nextcard.NextCard;
import com.klze.nextcard.common.load.CardContentReload;
import com.klze.nextcard.common.load.ContentBundle;
import com.klze.nextcard.core.player.CardLedger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 玩家卡账的存档与挂接（1.20.1 的 {@code Capability} 形态）。
 *
 * <p>为什么不直接往实体上写裸 NBT：裸 NBT 没有可观察的写入点，每次改动的通知与同步都会静默漏掉，
 * 而本模组的设计（重算 → 差量 → 通知）依赖"所有变更都经过同一个持有者"。这条口径是从
 * Spire Powers 那边同一个坑带过来的。</p>
 *
 * <p>NBT 只在 {@link #serializeNBT()} / {@link #deserializeNBT} 这一层出现，里面一律走
 * {@link CardLedger} 的文本形态——两侧各拼一遍 key 是第二真相的常见来源。</p>
 */
public final class PlayerCardState {

    public static final Capability<CardLedger> CARD_LEDGER = CapabilityManager.get(new CapabilityToken<>() {
    });

    /** 实体存档里这份数据的键名。 */
    public static final String NBT_KEY = "nextcard:cards";
    private static final String TAG_SCHEMA = "schema";
    private static final String TAG_OWNED = "owned";
    private static final String TAG_DRAWS = "draws";
    private static final String TAG_COUNTERS = "counters";

    private PlayerCardState() {
    }

    /**
     * 取玩家当前的卡账。返回 {@code null} 表示 capability 根本没挂上——调用方必须能把这件事和
     * "挂着但零张卡"区分开，否则"挂错总线"会以"玩家莫名其妙没卡"的形式出现。
     */
    public static @Nullable CardLedger of(Player player) {
        return player.getCapability(CARD_LEDGER).orElse(null);
    }

    /** 唯一的写档处。 */
    public static CompoundTag saveOf(CardLedger ledger) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(TAG_SCHEMA, CardLedger.SCHEMA_VERSION);
        ListTag owned = new ListTag();
        for (String id : ledger.ownedAsText()) {
            owned.add(StringTag.valueOf(id));
        }
        tag.put(TAG_OWNED, owned);
        tag.putInt(TAG_DRAWS, ledger.drawCount());
        CompoundTag counters = new CompoundTag();
        for (Map.Entry<String, Integer> entry : ledger.countersAsText().entrySet()) {
            counters.putInt(entry.getKey(), entry.getValue());
        }
        tag.put(TAG_COUNTERS, counters);
        return tag;
    }

    /** 唯一的读档处。内容还没加载时一律按保留处理，绝不因为查不到就丢卡。 */
    public static void loadInto(CardLedger ledger, CompoundTag tag) {
        if (tag.getInt(TAG_SCHEMA) > CardLedger.SCHEMA_VERSION) {
            return;     // 新档被旧模组读到：不猜格式，整份不动
        }
        List<String> ids = new ArrayList<>();
        ListTag owned = tag.getList(TAG_OWNED, Tag.TAG_STRING);
        for (int i = 0; i < owned.size(); i++) {
            ids.add(owned.getString(i));
        }
        Map<String, Integer> counters = new TreeMap<>();
        if (tag.contains(TAG_COUNTERS, Tag.TAG_COMPOUND)) {
            CompoundTag stored = tag.getCompound(TAG_COUNTERS);
            for (String key : stored.getAllKeys()) {
                counters.put(key, stored.getInt(key));
            }
        }
        ContentBundle content = CardContentReload.current();
        ledger.loadFrom(ids, tag.getInt(TAG_DRAWS), counters,
                content.isEmpty() ? null : content.cards().byId().keySet());
    }

    public static final class Provider implements ICapabilitySerializable<CompoundTag> {

        private final CardLedger ledger = new CardLedger();
        private final LazyOptional<CardLedger> optional = LazyOptional.of(() -> ledger);

        @Override
        public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
            return CARD_LEDGER == capability ? optional.cast() : LazyOptional.empty();
        }

        @Override
        public CompoundTag serializeNBT() {
            return saveOf(ledger);
        }

        @Override
        public void deserializeNBT(CompoundTag tag) {
            loadInto(ledger, tag);
        }

        public CardLedger ledger() {
            return ledger;
        }

        public void invalidate() {
            optional.invalidate();
        }
    }

    /** 由 {@code AttachCapabilitiesEvent<Entity>} 调用：只对玩家挂，别给每只怪也建一份。 */
    public static Provider attach(Entity entity) {
        return entity instanceof Player ? new Provider() : null;
    }
}
