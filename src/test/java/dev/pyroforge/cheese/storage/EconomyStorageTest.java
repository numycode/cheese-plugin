package dev.pyroforge.cheese.storage;

import java.io.File;
import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyStorageTest {

    @TempDir
    File tempDir;

    private EconomyStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        storage = new EconomyStorage(new File(tempDir, "cheese.db"));
    }

    @AfterEach
    void tearDown() throws Exception {
        storage.close();
    }

    @Test
    void startsUnseededWithZeroSupply() throws Exception {
        assertFalse(storage.isSeeded());
        assertEquals(0, storage.getCurrentSupply());
        assertEquals(0, storage.getMaxSupply());
    }

    @Test
    void seedInitialStateSetsBothValuesAndFlag() throws Exception {
        storage.seedInitialState(150, 8100);

        assertTrue(storage.isSeeded());
        assertEquals(150, storage.getCurrentSupply());
        assertEquals(8100, storage.getMaxSupply());
    }

    @Test
    void setMaxSupplyLeavesCurrentSupplyUntouched() throws Exception {
        storage.seedInitialState(150, 8100);

        storage.setMaxSupply(9000);

        assertEquals(150, storage.getCurrentSupply());
        assertEquals(9000, storage.getMaxSupply());
    }

    @Test
    void setMaxSupplyRejectsNegativeValues() throws Exception {
        storage.seedInitialState(150, 8100);

        assertThrows(IllegalArgumentException.class, () -> storage.setMaxSupply(-1));
        assertEquals(8100, storage.getMaxSupply());
    }

    @Test
    void setMaxSupplyAllowsSettingBelowCurrentSupply() throws Exception {
        // Deliberately allowed — an admin tightening the cap without an immediate clawback is a
        // legitimate distinct action from /cheese remove (see EconomyStorage.setMaxSupply's doc).
        storage.seedInitialState(150, 8100);

        storage.setMaxSupply(100);

        assertEquals(150, storage.getCurrentSupply());
        assertEquals(100, storage.getMaxSupply());
    }

    @Test
    void mintRaisesBothCurrentAndMaxSupplyByTheSameAmount() throws Exception {
        storage.seedInitialState(150, 8100);

        storage.mint(50);

        assertEquals(200, storage.getCurrentSupply());
        assertEquals(8150, storage.getMaxSupply());
    }

    @Test
    void destroyLowersBothCurrentAndMaxSupplyByTheSameAmount() throws Exception {
        storage.seedInitialState(150, 8100);

        storage.destroy(50);

        assertEquals(100, storage.getCurrentSupply());
        assertEquals(8050, storage.getMaxSupply());
    }

    @Test
    void tryIncreaseCurrentSupplyExactAdmitsWhenThereIsRoom() throws Exception {
        storage.seedInitialState(150, 200);

        boolean admitted = storage.tryIncreaseCurrentSupplyExact(50);

        assertTrue(admitted);
        assertEquals(200, storage.getCurrentSupply());
        assertEquals(200, storage.getMaxSupply());
    }

    @Test
    void tryIncreaseCurrentSupplyExactRejectsAllOrNothingWhenItWouldExceedTheCap() throws Exception {
        storage.seedInitialState(150, 200);

        boolean admitted = storage.tryIncreaseCurrentSupplyExact(51);

        assertFalse(admitted);
        assertEquals(150, storage.getCurrentSupply());
    }

    @Test
    void tryIncreaseCurrentSupplyGrantsTheFullAmountWhenItFits() throws Exception {
        storage.seedInitialState(150, 8100);

        long granted = storage.tryIncreaseCurrentSupply(50);

        assertEquals(50, granted);
        assertEquals(200, storage.getCurrentSupply());
    }

    @Test
    void tryIncreaseCurrentSupplyPartiallyGrantsWhenOnlySomeFits() throws Exception {
        storage.seedInitialState(150, 200);

        long granted = storage.tryIncreaseCurrentSupply(100);

        assertEquals(50, granted);
        assertEquals(200, storage.getCurrentSupply());
    }

    @Test
    void tryIncreaseCurrentSupplyGrantsNothingWhenAlreadyAtCap() throws Exception {
        storage.seedInitialState(200, 200);

        long granted = storage.tryIncreaseCurrentSupply(10);

        assertEquals(0, granted);
        assertEquals(200, storage.getCurrentSupply());
    }

    @Test
    void decreaseCurrentSupplyLowersItWithoutTouchingTheCap() throws Exception {
        storage.seedInitialState(150, 8100);

        storage.decreaseCurrentSupply(50);

        assertEquals(100, storage.getCurrentSupply());
        assertEquals(8100, storage.getMaxSupply());
    }

    @Test
    void decreaseCurrentSupplyNeverGoesNegative() throws Exception {
        storage.seedInitialState(10, 8100);

        storage.decreaseCurrentSupply(50);

        assertEquals(0, storage.getCurrentSupply());
    }

    @Test
    void stateSurvivesReopeningTheSameFile() throws Exception {
        File dbFile = new File(tempDir, "persist.db");
        try (EconomyStorage first = new EconomyStorage(dbFile)) {
            first.seedInitialState(42, 900);
        }

        try (EconomyStorage reopened = new EconomyStorage(dbFile)) {
            assertTrue(reopened.isSeeded());
            assertEquals(42, reopened.getCurrentSupply());
            assertEquals(900, reopened.getMaxSupply());
        }
    }
}
