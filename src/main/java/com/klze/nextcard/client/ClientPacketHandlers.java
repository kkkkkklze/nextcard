package com.klze.nextcard.client;

import com.klze.nextcard.common.config.ModClientConfig;
import com.klze.nextcard.common.network.message.NextCardGreetingMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Handles packets that arrive on the client. Kept in {@code client/} so the packet classes
 * themselves stay loadable on a dedicated server.
 */
public final class ClientPacketHandlers {
    private ClientPacketHandlers() {
    }

    /** Called from {@link NextCardGreetingMessage#handle()} via DistExecutor, on the client only. */
    public static void handleGreeting(NextCardGreetingMessage message) {
        if (!ModClientConfig.showExampleGreeting) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(message.text()), false);
        }
    }
}
