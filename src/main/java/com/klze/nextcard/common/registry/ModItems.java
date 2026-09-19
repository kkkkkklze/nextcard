package com.klze.nextcard.common.registry;

import com.klze.nextcard.NextCard;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** All items of this mod, including the {@link BlockItem}s of {@link ModBlocks}. */
public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, NextCard.MODID);

    public static final RegistryObject<Item> EXAMPLE_ITEM = ITEMS.register("example_item",
            () -> new Item(new Item.Properties().food(new FoodProperties.Builder()
                    .alwaysEat()
                    .nutrition(1)
                    .saturationMod(2F)
                    .build())));

    public static final RegistryObject<BlockItem> EXAMPLE_BLOCK_ITEM =
            registerBlockItem("example_block", ModBlocks.EXAMPLE_BLOCK);

    private ModItems() {
    }

    /**
     * Registers the {@link BlockItem} of a block.
     *
     * <p>A block without a BlockItem cannot be held or placed and NeoForge will complain about an
     * empty item in creative tabs, so keep this pairing in one place instead of repeating the
     * boilerplate for every block.</p>
     */
    public static RegistryObject<BlockItem> registerBlockItem(String name, RegistryObject<Block> block) {
        return ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
