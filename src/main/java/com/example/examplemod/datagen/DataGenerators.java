package com.example.examplemod.datagen;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.datagen.lang.ModLanguageProvider;
import com.example.examplemod.datagen.loot.ModLootTableProvider;
import com.example.examplemod.datagen.model.ModBlockStateProvider;
import com.example.examplemod.datagen.model.ModItemModelProvider;
import com.example.examplemod.datagen.recipe.ModRecipeProvider;
import com.example.examplemod.datagen.tag.ModBlockTagsProvider;
import com.example.examplemod.datagen.tag.ModItemTagsProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraftforge.common.data.ExistingFileHelper;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.concurrent.CompletableFuture;

/**
 * Data generation entry point. Run {@code gradlew runData} and everything below is written to
 * {@code src/generated/resources} (which is committed and included in the jar).
 *
 * <p>Why this matters: hand written json is where mods rot. Generated models, language files, loot
 * tables, recipes and tags always match the registry names in code, and adding content becomes one
 * line per thing instead of four json files.</p>
 *
 * <p>{@code --existing src/main/resources} (see the {@code data} run in build.gradle) keeps
 * generation from silently overwriting hand written files, so both kinds of resources can coexist.
 * The {@code .cache} folder next to the generated output is Gradle's "what did I generate last
 * time" bookkeeping - commit it, exclude it from the jar.</p>
 */
@Mod.EventBusSubscriber(modid = ExampleMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class DataGenerators {
    private DataGenerators() {
    }

    @SubscribeEvent
    public static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        PackOutput output = generator.getPackOutput();
        ExistingFileHelper existingFileHelper = event.getExistingFileHelper();
        CompletableFuture<HolderLookup.Provider> lookupProvider = event.getLookupProvider();

        // Client resources: block states, models, language files.
        generator.addProvider(event.includeClient(), new ModBlockStateProvider(output, existingFileHelper));
        generator.addProvider(event.includeClient(), new ModItemModelProvider(output, existingFileHelper));
        generator.addProvider(event.includeClient(), new ModLanguageProvider(output, "en_us"));
        generator.addProvider(event.includeClient(), new ModLanguageProvider(output, "zh_cn"));

        // Server data: recipes, loot tables, tags.
        generator.addProvider(event.includeServer(), new ModRecipeProvider(output));
        generator.addProvider(event.includeServer(), new ModLootTableProvider(output));

        ModBlockTagsProvider blockTags = new ModBlockTagsProvider(output, lookupProvider, existingFileHelper);
        generator.addProvider(event.includeServer(), blockTags);
        generator.addProvider(event.includeServer(),
                new ModItemTagsProvider(output, lookupProvider, blockTags.contentsGetter(), existingFileHelper));
    }
}
