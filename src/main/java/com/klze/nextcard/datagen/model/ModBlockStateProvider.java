package com.klze.nextcard.datagen.model;

import com.klze.nextcard.NextCard;
import com.klze.nextcard.common.registry.ModBlocks;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.model.generators.BlockStateProvider;
import net.minecraftforge.common.data.ExistingFileHelper;

/**
 * Block states + block models.
 *
 * <p>Note how the model borrows a vanilla texture ({@code minecraft:block/stone}) so the example
 * block renders correctly without shipping any texture. Replace it with
 * {@code modLoc("block/" + name)} once you add {@code assets/<modid>/textures/block/<name>.png}.</p>
 */
public class ModBlockStateProvider extends BlockStateProvider {
    public ModBlockStateProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, NextCard.MODID, existingFileHelper);
    }

    @Override
    protected void registerStatesAndModels() {
        String name = ModBlocks.EXAMPLE_BLOCK.getId().getPath();
        ResourceLocation stone = mcLoc("block/stone");
        simpleBlock(ModBlocks.EXAMPLE_BLOCK.get(), models().cubeAll(name, stone));
    }
}
