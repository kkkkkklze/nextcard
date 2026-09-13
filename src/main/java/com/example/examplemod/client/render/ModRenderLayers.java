package com.example.examplemod.client.render;

import com.example.examplemod.common.registry.ModBlocks;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;

/**
 * Client-only render setup.
 *
 * <p>Anything that references {@code net.minecraft.client.*} belongs in {@code client/} - putting it
 * in common code compiles fine and then crashes the dedicated server at runtime.</p>
 */
public final class ModRenderLayers {
    private ModRenderLayers() {
    }

    public static void register() {
        // Cutout rendering: needed for textures with transparency (leaves, glass, plants). Our
        // example block is a solid stone-like cube, so this is a no-op visually - replace it or drop
        // the call once you add a block that actually needs a non-solid layer.
        ItemBlockRenderTypes.setRenderLayer(ModBlocks.EXAMPLE_BLOCK.get(), RenderType.cutout());
    }
}
