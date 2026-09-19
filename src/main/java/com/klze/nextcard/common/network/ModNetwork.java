package com.klze.nextcard.common.network;

import com.klze.nextcard.NextCard;
import com.klze.nextcard.common.network.message.NextCardGreetingMessage;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Every packet of this mod goes through one {@link SimpleChannel}.
 *
 * <p>1.20.1 uses the old {@code SimpleChannel} API - <b>not</b> the {@code CustomPacketPayload} /
 * {@code PayloadRegistrar} API you will find in 1.20.2+ tutorials. Copying those here will not
 * compile.</p>
 *
 * <p>Rules of thumb for this API:</p>
 * <ul>
 *   <li><b>Bump {@link #PROTOCOL_VERSION} whenever a packet's fields change</b>, and compare it on
 *       both sides. A one-sided check lets an outdated client connect and crash on its first
 *       packet.</li>
 *   <li>Packet ids are the {@code int} passed to {@code messageBuilder} - never renumber existing
 *       packets, always append.</li>
 *   <li>Handlers must not touch the world on the network thread. {@code consumerMainThread} takes
 *       care of hopping to the main thread (and marking the packet handled); {@code
 *       consumerNetworkThread} does <b>not</b> - then you must call {@code enqueueWork} yourself.
 *       See {@link NextCardGreetingMessage}.</li>
 *   <li>Packets are plain classes with an {@code encode}/{@code decode} pair; the decoder runs on
 *       the network thread and must only read bytes.</li>
 * </ul>
 */
public final class ModNetwork {
    /** Bump on every packet format change. */
    public static final String PROTOCOL_VERSION = "1";

    private static final ResourceLocation CHANNEL_NAME = new ResourceLocation(NextCard.MODID, "main");

    private static SimpleChannel channel;

    private ModNetwork() {
    }

    /** Called from the mod constructor, before anything can send a packet. */
    public static void register() {
        SimpleChannel newChannel = NetworkRegistry.ChannelBuilder.named(CHANNEL_NAME)
                // Strict versions: a client without the mod (or with a different version) is refused
                // with a readable message. Use NetworkRegistry.ACCEPTVANILLA instead if your mod
                // must allow vanilla clients to connect.
                .clientAcceptedVersions(PROTOCOL_VERSION::equals)
                .serverAcceptedVersions(PROTOCOL_VERSION::equals)
                .networkProtocolVersion(() -> PROTOCOL_VERSION)
                .simpleChannel();

        int id = 0;
        newChannel.messageBuilder(NextCardGreetingMessage.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(NextCardGreetingMessage::encode)
                .decoder(NextCardGreetingMessage::decode)
                .consumerMainThread(NextCardGreetingMessage::handle)
                .add();

        channel = newChannel;
    }

    /** Server -> one player. */
    public static void sendToPlayer(ServerPlayer player, Object message) {
        channel.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    /** Client -> server. */
    public static void sendToServer(Object message) {
        channel.sendToServer(message);
    }
}
