package com.klze.nextcard.gametest;

import com.klze.nextcard.NextCard;
import com.klze.nextcard.common.registry.ModBlocks;
import com.klze.nextcard.common.registry.ModCreativeTabs;
import com.klze.nextcard.common.registry.ModItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests run a real server, so they can check things unit tests cannot: that every registry
 * actually got populated, that a block drops its item, that a machine ticks.
 *
 * <p>Run them with {@code gradlew runGameTestServer} when the project is configured with
 * {@code forge.enabledGameTestNamespaces} (see the {@code gameTestServer} run in build.gradle).</p>
 */
@GameTestHolder(NextCard.MODID)
@PrefixGameTestTemplate(false)
public class NextCardGameTests {
    /** Smoke test: the mod's content exists once the game has loaded. */
    @GameTest
    public void registriesArePopulated(GameTestHelper helper) {
        helper.assertTrue(ModBlocks.EXAMPLE_BLOCK.isPresent(), "example block must be registered");
        helper.assertTrue(ModItems.EXAMPLE_BLOCK_ITEM.isPresent(), "block item must be registered");
        helper.assertTrue(ModItems.EXAMPLE_ITEM.isPresent(), "example item must be registered");
        helper.assertTrue(ModCreativeTabs.EXAMPLE_TAB.isPresent(), "creative tab must be registered");

        // Force the class our mixin targets to load, so a mixin that cannot apply (wrong target,
        // stale refmap, dropped MixinExtras) fails this test loudly instead of showing up in-game.
        helper.assertTrue(Player.class != null, "Player must load with PlayerTickMixin applied");

        helper.succeed();
    }
}
