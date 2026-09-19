package com.klze.nextcard.datagen.loot;

import net.minecraft.data.PackOutput;
import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;

import java.util.List;
import java.util.Set;

/** Registers the loot sub-providers. Add more (chest loot, entity drops) to the list below. */
public class ModLootTableProvider extends LootTableProvider {
    public ModLootTableProvider(PackOutput output) {
        super(output, Set.of(),
                List.of(new SubProviderEntry(ModBlockLootTables::new, LootContextParamSets.BLOCK)));
    }
}
