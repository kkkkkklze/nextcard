package com.example.examplemod.common;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.common.config.ModCommonConfig;
import com.example.examplemod.common.network.ModNetwork;
import com.example.examplemod.common.network.message.ExampleGreetingMessage;
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
@Mod.EventBusSubscriber(modid = ExampleMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ExampleModEvents {
    private ExampleModEvents() {
    }

    /** Sends the example packet to every player that joins - the other half of the network demo. */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ModNetwork.sendToPlayer(player, new ExampleGreetingMessage(ModCommonConfig.exampleGreeting));
        }
    }
}
