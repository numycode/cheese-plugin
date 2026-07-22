package dev.pyroforge.cheese.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CheeseUnitsTest {

    @Test
    void oneIngotIsNineUnits() {
        assertEquals(9, CheeseUnits.ingotsToUnits(1));
    }

    @Test
    void oneNuggetIsOneUnit() {
        assertEquals(1, CheeseUnits.nuggetsToUnits(1));
    }

    @Test
    void nineNuggetsEqualsOneIngotInUnits() {
        assertEquals(CheeseUnits.ingotsToUnits(1), CheeseUnits.nuggetsToUnits(9));
    }
}
