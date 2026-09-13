package com.example.examplemod.client;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.client.render.ModRenderLayers;
import com.example.examplemod.common.config.ModClientConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Client-only setup. The whole package {@code client/} may only ever be loaded on the client, and
 * everything in it may reference classes from {@code net.minecraft.client.*}.
 *
 * <p>Two ways to keep the two sides apart, both used here:</p>
 * <ul>
 *   <li>{@code value = Dist.CLIENT} on {@link Mod.EventBusSubscriber}: Forge reads the annotation
 *       without loading the class, so a dedicated server will not even try to load this file.</li>
 *   <li>The {@code FMLEnvironment.dist.isClient()} check in the mod constructor - the usual guard
 *       for direct calls from common code.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = ExampleMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    private ClientSetup() {
    }

    /** Called from the mod constructor when running on a client. */
    public static void init(IEventBus modEventBus) {
        // CLIENT config only exists on the client, so it is registered here and not in the main class.
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ModClientConfig.SPEC);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Most client setup must run after the registries are frozen - use enqueueWork.
        event.enqueueWork(ModRenderLayers::register);
    }
}
