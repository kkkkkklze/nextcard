package com.klze.nextcard.common.registry;

import com.klze.nextcard.NextCard;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Supplier;

/**
 * All blocks of this mod.
 *
 * <p>One registry = one class: keep every {@code DeferredRegister} in its own file and let the main
 * class only call {@link #register(IEventBus)}. This keeps the main class readable and makes it
 * obvious where new content belongs.</p>
 */
public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, NextCard.MODID);

    public static final RegistryObject<Block> EXAMPLE_BLOCK = register("example_block",
            () -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(1.5F, 6.0F)
                    .sound(SoundType.STONE)));

    private ModBlocks() {
    }

    private static <T extends Block> RegistryObject<T> register(String name, Supplier<T> supplier) {
        return BLOCKS.register(name, supplier);
    }

    /** Called from the mod constructor. Forgetting this call fails silently at compile time. */
    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }
}
