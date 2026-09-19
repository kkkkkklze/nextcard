package com.klze.nextcard.datagen.loot;

import com.klze.nextcard.common.registry.ModBlocks;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Block loot tables.
 *
 * <p>{@link #getKnownBlocks()} exists so the generator can warn about blocks that were registered
 * but never got a loot table - a silent "my block drops nothing" bug.</p>
 */
public class ModBlockLootTables extends BlockLootSubProvider {
    public ModBlockLootTables() {
        super(Set.of(), FeatureFlags.REGISTRY.allFlags());
    }

    @Override
    protected void generate() {
        dropSelf(ModBlocks.EXAMPLE_BLOCK.get());
    }

    @Override
    protected Iterable<Block> getKnownBlocks() {
        List<Block> blocks = ModBlocks.BLOCKS.getEntries().stream()
                .map(RegistryObject::get)
                .collect(Collectors.toList());
        return blocks;
    }
}
