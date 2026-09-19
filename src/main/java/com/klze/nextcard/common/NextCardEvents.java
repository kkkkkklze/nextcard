package com.klze.nextcard.common;

import com.klze.nextcard.NextCard;
import com.klze.nextcard.common.config.ModCommonConfig;
import com.klze.nextcard.common.network.ModNetwork;
import com.klze.nextcard.common.network.message.NextCardGreetingMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Gameplay events (Forge bus: {@code MinecraftForge.EVENT_BUS}), as opposed to the mod bus used for
 * registries, configs and data generation.
 *
 * <p>Splitting the two buses is the single most common source of "my listener never fires": if you
 * subscribed to the wrong bus nothing happens and nothing warns you.</p>
 */
@Mod.EventBusSubscriber(modid = NextCard.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class NextCardEvents {
    private NextCardEvents() {
    }

    /** Sends the example packet to every player that joins - the other half of the network demo. */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ModNetwork.sendToPlayer(player, new NextCardGreetingMessage(ModCommonConfig.exampleGreeting));
        }
    }
}
