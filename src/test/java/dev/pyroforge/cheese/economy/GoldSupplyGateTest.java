package dev.pyroforge.cheese.economy;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import dev.pyroforge.cheese.storage.EconomyStorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldSupplyGateTest {

    @TempDir
    File tempDir;

    private EconomyStorage storage;
    private GoldSupplyGate gate;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        storage = new EconomyStorage(new File(tempDir, "cheese.db"));
        gate = new GoldSupplyGate(storage, new GoldCounter(10));
    }

    @AfterEach
    void tearDown() throws Exception {
        storage.close();
        MockBukkit.unmock();
    }

    @Test
    void admitsASingleStackThatFitsUnderTheCap() throws Exception {
        storage.seedInitialState(0, 100);

        boolean admitted = gate.admitSingleStack(new ItemStack(Material.GOLD_INGOT, 1));

        assertTrue(admitted);
        assertEquals(9, storage.getCurrentSupply());
    }

    @Test
    void rejectsASingleStackThatWouldExceedTheCapWithoutPartiallyAdmittingIt() throws Exception {
        storage.seedInitialState(95, 100);

        // 1 ingot = 9 units, would push currentSupply to 104 > 100.
        boolean admitted = gate.admitSingleStack(new ItemStack(Material.GOLD_INGOT, 1));

        assertFalse(admitted);
        assertEquals(95, storage.getCurrentSupply());
    }

    @Test
    void ignoresNonGoldStacks() throws Exception {
        storage.seedInitialState(0, 0);

        boolean admitted = gate.admitSingleStack(new ItemStack(Material.DIAMOND, 64));

        assertTrue(admitted);
        assertEquals(0, storage.getCurrentSupply());
    }

    @Test
    void admitsAFullBatchThatFitsUnderTheCapUnmodified() throws Exception {
        storage.seedInitialState(0, 100);
        List<ItemStack> drops = new ArrayList<>(List.of(
                new ItemStack(Material.GOLD_NUGGET, 5),
                new ItemStack(Material.ROTTEN_FLESH, 1)));

        gate.admitList(drops);

        assertEquals(5, storage.getCurrentSupply());
        assertEquals(2, drops.size());
        assertEquals(5, drops.get(0).getAmount());
    }

    @Test
    void trimsAnOverCapBatchDownToWhatFitsAndLeavesNonGoldAlone() throws Exception {
        storage.seedInitialState(95, 100);
        List<ItemStack> drops = new ArrayList<>(List.of(
                new ItemStack(Material.GOLD_NUGGET, 10),
                new ItemStack(Material.ROTTEN_FLESH, 1)));

        gate.admitList(drops);

        assertEquals(100, storage.getCurrentSupply());
        assertEquals(2, drops.size());
        assertEquals(5, drops.get(0).getAmount());
        assertEquals(Material.ROTTEN_FLESH, drops.get(1).getType());
    }

    @Test
    void removesAGoldStackEntirelyWhenNoneOfItFits() throws Exception {
        storage.seedInitialState(100, 100);
        List<ItemStack> drops = new ArrayList<>(List.of(new ItemStack(Material.GOLD_NUGGET, 5)));

        gate.admitList(drops);

        assertEquals(100, storage.getCurrentSupply());
        assertTrue(drops.isEmpty());
    }

    @Test
    void trimsOnlyWholeIngotsNeverPartialOnesWhenAnIngotStackIsPartlyOverCap() throws Exception {
        storage.seedInitialState(85, 100);
        // 2 ingots = 18 units requested, but only 15 fit -> 15 / 9 = 1 whole ingot kept.
        List<ItemStack> drops = new ArrayList<>(List.of(new ItemStack(Material.GOLD_INGOT, 2)));

        gate.admitList(drops);

        assertEquals(94, storage.getCurrentSupply());
        assertEquals(1, drops.get(0).getAmount());
    }
}
