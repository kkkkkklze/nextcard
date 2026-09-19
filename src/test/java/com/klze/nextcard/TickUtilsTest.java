package com.klze.nextcard;

import com.klze.nextcard.common.util.TickUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for pure game logic.
 *
 * <p>These run with plain {@code gradlew test} - no game, no bootstrap. Keep anything that does not
 * need a live registry in classes like {@link TickUtils} and it can be tested this way.</p>
 */
public class TickUtilsTest {
    @Test
    void secondsToTicksRoundsToNearestTick() {
        assertEquals(600, TickUtils.secondsToTicks(30));
        assertEquals(20, TickUtils.secondsToTicks(1));
        assertEquals(1, TickUtils.secondsToTicks(0.05));
    }

    @Test
    void ticksToSecondsIsTheInverse() {
        assertEquals(1.5D, TickUtils.ticksToSeconds(30), 0.0001D);
        assertEquals(30D, TickUtils.ticksToSeconds(TickUtils.secondsToTicks(30)), 0.0001D);
    }
}
