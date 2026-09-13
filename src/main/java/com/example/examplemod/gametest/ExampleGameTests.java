package com.example.examplemod.gametest;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.common.registry.ModBlocks;
import com.example.examplemod.common.registry.ModCreativeTabs;
import com.example.examplemod.common.registry.ModItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests run a real server, so they can check things unit tests cannot: that every registry
 * actually got populated, that a block drops its item, that a machine ticks.
 *
 * <p>Run them with {@code gradlew runGameTestServer} when the project is configured with
 * {@code forge.enabledGameTestNamespaces} (see the {@code gameTestServer} run in build.gradle).</p>
 */
@GameTestHolder(ExampleMod.MODID)
@PrefixGameTestTemplate(false)
public class ExampleGameTests {
    /** Smoke test: the mod's content exists once the game has loaded. */
    @GameTest
    public void registriesArePopulated(GameTestHelper helper) {
        helper.assertTrue(ModBlocks.EXAMPLE_BLOCK.isPresent(), "example block must be registered");
        helper.assertTrue(ModItems.EXAMPLE_BLOCK_ITEM.isPresent(), "block item must be registered");
        helper.assertTrue(ModItems.EXAMPLE_ITEM.isPresent(), "example item must be registered");
        helper.assertTrue(ModCreativeTabs.EXAMPLE_TAB.isPresent(), "creative tab must be registered");
        helper.succeed();
    }
}
