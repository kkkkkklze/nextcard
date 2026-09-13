package com.example.examplemod.datagen.tag;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.common.registry.ModItems;
import com.example.examplemod.common.tags.ModItemTags;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.ItemTagsProvider;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.data.ExistingFileHelper;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * Item tags.
 *
 * <p>Note that 1.20.1 has no {@code minecraft:mineable/*} <i>item</i> tags - mining speed is driven
 * by the block tags generated in {@link ModBlockTagsProvider}. Only mirror a block tag into an item
 * tag when the tag is actually read on items (tool tiers, {@code forge:*} tags, your own tags).</p>
 */
public class ModItemTagsProvider extends ItemTagsProvider {
    public ModItemTagsProvider(PackOutput output,
                               CompletableFuture<HolderLookup.Provider> lookupProvider,
                               CompletableFuture<TagsProvider.TagLookup<Block>> blockTags,
                               @Nullable ExistingFileHelper existingFileHelper) {
        super(output, lookupProvider, blockTags, ExampleMod.MODID, existingFileHelper);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        tag(ModItemTags.EXAMPLE_ITEMS).add(ModItems.EXAMPLE_ITEM.get(), ModItems.EXAMPLE_BLOCK_ITEM.get());
    }
}
