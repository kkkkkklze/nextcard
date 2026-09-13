package com.example.examplemod.common.config;

import com.example.examplemod.ExampleMod;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/**
 * COMMON config (loaded on both client and dedicated server, saved per instance).
 *
 * <p>Keep one class per config type and split by subsystem once the file grows. Values are cached
 * in plain static fields and refreshed on {@link ModConfigEvent} so hot paths (tick handlers,
 * network handlers) never walk the spec.</p>
 */
@Mod.EventBusSubscriber(modid = ExampleMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModCommonConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue LOG_PLAYER_TICK_DEMO = BUILDER
            .comment("Enable the mixin demo in PlayerTickMixin: logs one line every 30 seconds per player.")
            .define("logPlayerTickDemo", false);

    public static final ForgeConfigSpec.IntValue EXAMPLE_MAGIC_NUMBER = BUILDER
            .comment("Example integer option, shown in the config file.")
            .defineInRange("exampleMagicNumber", 42, 0, Integer.MAX_VALUE);

    public static final ForgeConfigSpec.ConfigValue<String> EXAMPLE_GREETING = BUILDER
            .comment("Message sent to a player when they join the server (see ExampleModEvents).")
            .define("exampleGreeting", "Hello from examplemod!");

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    // Cached values - read these from game code, not the spec.
    public static boolean logPlayerTickDemo;
    public static int exampleMagicNumber = 42;
    public static String exampleGreeting = "Hello from examplemod!";

    private ModCommonConfig() {
    }

    @SubscribeEvent
    static void onConfigLoad(final ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            logPlayerTickDemo = LOG_PLAYER_TICK_DEMO.get();
            exampleMagicNumber = EXAMPLE_MAGIC_NUMBER.get();
            exampleGreeting = EXAMPLE_GREETING.get();
        }
    }
}
