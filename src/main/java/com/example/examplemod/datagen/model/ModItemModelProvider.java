package com.example.examplemod.datagen.model;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.common.registry.ModBlocks;
import com.example.examplemod.common.registry.ModItems;
import net.minecraft.data.PackOutput;
import net.minecraftforge.client.model.generators.ItemModelProvider;
import net.minecraftforge.common.data.ExistingFileHelper;

/**
 * Item models.
 *
 * <p>The block item reuses its block model. The plain item borrows a vanilla texture
 * ({@code minecraft:item/apple}) so the example renders out of the box - swap it for
 * {@code modLoc("item/example_item")} once you ship {@code assets/<modid>/textures/item/example_item.png}.
 * Without a model the game shows the purple/black "missing model" block, which is exactly what the
 * original template did for its example block.</p>
 */
public class ModItemModelProvider extends ItemModelProvider {
    public ModItemModelProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, ExampleMod.MODID, existingFileHelper);
    }

    @Override
    protected void registerModels() {
        String blockName = ModBlocks.EXAMPLE_BLOCK.getId().getPath();
        withExistingParent(blockName, modLoc("block/" + blockName));

        withExistingParent(ModItems.EXAMPLE_ITEM.getId().getPath(), mcLoc("item/generated"))
                .texture("layer0", mcLoc("item/apple"));
    }
}
