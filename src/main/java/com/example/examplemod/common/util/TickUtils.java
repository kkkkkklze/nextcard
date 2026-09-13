package com.example.examplemod.common.util;

/**
 * Tick/time helpers. Minecraft runs 20 game ticks per second on both sides.
 *
 * <p>Keep game-logic helpers that do not touch Minecraft classes in small pure classes like this
 * one: they can be unit tested without booting the game (see {@code src/test/java}).</p>
 */
public final class TickUtils {
    public static final int TICKS_PER_SECOND = 20;

    private TickUtils() {
    }

    /** Converts seconds to game ticks (rounded). */
    public static int secondsToTicks(double seconds) {
        return (int) Math.round(seconds * TICKS_PER_SECOND);
    }

    /** Converts game ticks to seconds. */
    public static double ticksToSeconds(long ticks) {
        return ticks / (double) TICKS_PER_SECOND;
    }
}
