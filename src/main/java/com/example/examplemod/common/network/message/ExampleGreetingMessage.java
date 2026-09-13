package com.example.examplemod.common.network.message;

import com.example.examplemod.client.ClientPacketHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Example server -> client packet: sends one line of text to be shown in chat.
 *
 * <p>How the pieces fit together with Forge's {@code SimpleChannel}:</p>
 * <ul>
 *   <li>{@link #encode}/{@link #decode} run on the network thread - they may only read and write
 *       bytes, never touch the world, registries or the player.</li>
 *   <li>{@link #handle} is registered with {@code consumerMainThread}, which already wraps it in
 *       {@code context.enqueueWork(...)} and marks the packet handled. If you register with
 *       {@code consumerNetworkThread} instead, that part is your job - and forgetting it is the
 *       classic "random ConcurrentModificationException / world access off-thread" crash.</li>
 *   <li>Strings and collections are size limited ({@link #MAX_LENGTH} below). An unbounded read
 *       from the network is a trivial way for a client to kick everyone, which is why Jade caps its
 *       sync packets at 16 KB.</li>
 * </ul>
 */
public class ExampleGreetingMessage {
    private static final int MAX_LENGTH = 256;

    private final String text;

    public ExampleGreetingMessage(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }

    /** Decoder: runs on the network thread. */
    public static ExampleGreetingMessage decode(FriendlyByteBuf buf) {
        return new ExampleGreetingMessage(buf.readUtf(MAX_LENGTH));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(text, MAX_LENGTH);
    }

    /**
     * Runs on the main thread. Client-only code is reached through {@link DistExecutor}: referencing
     * {@link ClientPacketHandlers} directly would make a dedicated server fail to load this class
     * ({@code NoClassDefFoundError} for the client classes it pulls in) the moment a player joins.
     */
    public static void handle(ExampleGreetingMessage message, Supplier<NetworkEvent.Context> context) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.handleGreeting(message));
    }
}
