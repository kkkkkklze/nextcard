package com.example.examplemod.client;

import com.example.examplemod.common.config.ModClientConfig;
import com.example.examplemod.common.network.message.ExampleGreetingMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Handles packets that arrive on the client. Kept in {@code client/} so the packet classes
 * themselves stay loadable on a dedicated server.
 */
public final class ClientPacketHandlers {
    private ClientPacketHandlers() {
    }

    /** Called from {@link ExampleGreetingMessage#handle()} via DistExecutor, on the client only. */
    public static void handleGreeting(ExampleGreetingMessage message) {
        if (!ModClientConfig.showExampleGreeting) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(message.text()), false);
        }
    }
}
