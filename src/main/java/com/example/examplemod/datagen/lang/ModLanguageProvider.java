package com.example.examplemod.datagen.lang;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.common.registry.ModBlocks;
import com.example.examplemod.common.registry.ModCreativeTabs;
import com.example.examplemod.common.registry.ModItems;
import net.minecraft.data.PackOutput;
import net.minecraftforge.common.data.LanguageProvider;

/**
 * Language files. One instance per locale - {@link com.example.examplemod.datagen.DataGenerators}
 * creates {@code en_us} and {@code zh_cn}.
 *
 * <p>Keys are derived from the registry names, so a rename in code can never leave a stale
 * translation behind.</p>
 */
public class ModLanguageProvider extends LanguageProvider {
    private final String locale;

    public ModLanguageProvider(PackOutput output, String locale) {
        super(output, ExampleMod.MODID, locale);
        this.locale = locale;
    }

    @Override
    protected void addTranslations() {
        if ("zh_cn".equals(locale)) {
            addCreativeTab("示例模组");
            addBlock(ModBlocks.EXAMPLE_BLOCK, "示例方块");
            addItem(ModItems.EXAMPLE_ITEM, "示例物品");
        } else {
            addCreativeTab("Example Mod");
            addBlock(ModBlocks.EXAMPLE_BLOCK, "Example Block");
            addItem(ModItems.EXAMPLE_ITEM, "Example Item");
        }
    }

    private void addCreativeTab(String name) {
        add("itemGroup." + ExampleMod.MODID + "." + ModCreativeTabs.EXAMPLE_TAB.getId().getPath(), name);
    }
}
