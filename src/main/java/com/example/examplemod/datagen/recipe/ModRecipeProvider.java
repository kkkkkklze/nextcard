package com.example.examplemod.datagen.recipe;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.common.registry.ModItems;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.FinishedRecipe;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapelessRecipeBuilder;
import net.minecraft.data.recipes.SimpleCookingRecipeBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.conditions.IConditionBuilder;

import java.util.function.Consumer;

/**
 * Recipes. Two examples that cover most needs: a shapeless recipe and a smelting recipe.
 *
 * <p>{@code has(...)} and the other recipe helpers are protected members of {@link RecipeProvider},
 * so they are used directly here instead of via a static import.</p>
 *
 * <p>Implementing {@link IConditionBuilder} is optional but handy: it lets you wrap a recipe in a
 * condition (e.g. "only load if mod X is present", or a config toggle) instead of shipping it
 * unconditionally.</p>
 */
public class ModRecipeProvider extends RecipeProvider implements IConditionBuilder {
    public ModRecipeProvider(PackOutput output) {
        super(output);
    }

    @Override
    protected void buildRecipes(Consumer<FinishedRecipe> writer) {
        // Crafting: 1 example item from 1 dirt.
        ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, ModItems.EXAMPLE_ITEM.get())
                .requires(Items.DIRT)
                .unlockedBy("has_dirt", has(Items.DIRT))
                .save(writer, id("example_item_from_dirt"));

        // Furnace: dirt -> example item.
        SimpleCookingRecipeBuilder
                .smelting(Ingredient.of(Items.DIRT), RecipeCategory.MISC, ModItems.EXAMPLE_ITEM.get(), 0.1F, 200)
                .unlockedBy("has_dirt", has(Items.DIRT))
                .save(writer, id("example_item_from_smelting"));
    }

    private ResourceLocation id(String path) {
        return new ResourceLocation(ExampleMod.MODID, path);
    }
}
