package com.klze.nextcard;

import com.klze.nextcard.client.ClientSetup;
import com.klze.nextcard.common.config.ModCommonConfig;
import com.klze.nextcard.common.network.ModNetwork;
import com.klze.nextcard.common.registry.ModBlocks;
import com.klze.nextcard.common.registry.ModCreativeTabs;
import com.klze.nextcard.common.registry.ModItems;
import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * Mod entry point. It only wires things together - keep it that way, all real code lives in
 * {@code common/} (both sides) and {@code client/} (client only).
 */
@Mod(NextCard.MODID)
public class NextCard {
    public static final String MODID = "nextcard";
    public static final Logger LOGGER = LogUtils.getLogger();

    public NextCard() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Registries: one line per registry class.
        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModCreativeTabs.register(modEventBus);

        // Configs: COMMON here, CLIENT inside the client-only setup below.
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ModCommonConfig.SPEC);

        // Network: must happen before anything tries to send a packet.
        ModNetwork.register();

        // Client-only code must never be loaded on a dedicated server, so it sits behind this check.
        if (FMLEnvironment.dist.isClient()) {
            ClientSetup.init(modEventBus);
        }

        LOGGER.info("{} loaded", MODID);
    }
}
