package com.klze.nextcard.common.tags;

import com.klze.nextcard.NextCard;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * Item tags owned by this mod.
 *
 * <p>Keep tag keys in a class like this instead of inlining {@code ItemTags.create(...)} at every
 * use site - the same key string is needed by the tag generator, recipes and code.</p>
 */
public final class ModItemTags {
    /** Everything this mod adds, so packs and other mods can target it in one go. */
    public static final TagKey<Item> EXAMPLE_ITEMS =
            ItemTags.create(new ResourceLocation(NextCard.MODID, "example_items"));

    private ModItemTags() {
    }
}
