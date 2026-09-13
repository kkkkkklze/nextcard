package com.example.examplemod.common.config;

import com.example.examplemod.ExampleMod;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/**
 * CLIENT config (only exists on the client, saved per client instance).
 *
 * <p>Registered from {@code client/ClientSetup} so a dedicated server never creates the file.
 * 1.20.1 has no built-in config screen - if you want one, add a screen mod (Cloth Config,
 * Configured, ...) and hook it up yourself; the spec below stays the source of truth either way.</p>
 */
@Mod.EventBusSubscriber(modid = ExampleMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModClientConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue SHOW_EXAMPLE_GREETING = BUILDER
            .comment("Show the greeting packet sent by the server as a chat message.")
            .define("showExampleGreeting", true);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    public static boolean showExampleGreeting = true;

    private ModClientConfig() {
    }

    @SubscribeEvent
    static void onConfigLoad(final ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            showExampleGreeting = SHOW_EXAMPLE_GREETING.get();
        }
    }
}
