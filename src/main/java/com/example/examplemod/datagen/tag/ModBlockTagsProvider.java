package com.example.examplemod.datagen.tag;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.common.registry.ModBlocks;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.tags.BlockTags;
import net.minecraftforge.common.data.BlockTagsProvider;
import net.minecraftforge.common.data.ExistingFileHelper;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * Block tags. The two below are the ones almost every pickaxe-mined block needs; the matching item
 * tags are copied over in {@link ModItemTagsProvider}.
 */
public class ModBlockTagsProvider extends BlockTagsProvider {
    public ModBlockTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider,
                                @Nullable ExistingFileHelper existingFileHelper) {
        super(output, lookupProvider, ExampleMod.MODID, existingFileHelper);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        tag(BlockTags.MINEABLE_WITH_PICKAXE).add(ModBlocks.EXAMPLE_BLOCK.get());
        tag(BlockTags.NEEDS_STONE_TOOL).add(ModBlocks.EXAMPLE_BLOCK.get());
    }
}
