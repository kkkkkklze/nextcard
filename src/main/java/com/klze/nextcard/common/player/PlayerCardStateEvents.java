package com.klze.nextcard.common.player;

import com.klze.nextcard.NextCard;
import com.klze.nextcard.core.player.CardLedger;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 卡账的挂接与死亡复制（Forge 游戏总线）。
 *
 * <p>复制走 {@code serializeNBT → deserializeNBT} 而不是逐字段拷贝：这样"死亡后保留什么"
 * 只有一份定义（就是存档格式本身），不会出现"读档保留、死亡不保留"这种两套逻辑分叉。
 * 死亡与换维一体两面，规则也就不用分别维护。</p>
 */
@Mod.EventBusSubscriber(modid = NextCard.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PlayerCardStateEvents {

    private static final ResourceLocation KEY = new ResourceLocation(NextCard.MODID, "card_state");

    private PlayerCardStateEvents() {
    }

    @SubscribeEvent
    public static void onAttachCapabilities(AttachCapabilitiesEvent<net.minecraft.world.entity.Entity> event) {
        PlayerCardState.Provider provider = PlayerCardState.attach(event.getObject());
        if (provider != null) {
            event.addCapability(KEY, provider);
        }
    }

    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        CardLedger original = PlayerCardState.of(event.getOriginal());
        CardLedger replacement = PlayerCardState.of(event.getEntity());
        if (original == null || replacement == null) {
            // 挂接失败时绝不能静默跳过：那正是"死了以后卡全没了"最安静的表现方式
            throw new IllegalStateException("nextcard card capability missing on clone; original="
                    + (original != null) + " replacement=" + (replacement != null));
        }
        PlayerCardState.loadInto(replacement, PlayerCardState.saveOf(original));
    }
}
