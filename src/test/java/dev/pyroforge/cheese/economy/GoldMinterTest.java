package dev.pyroforge.cheese.economy;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GoldMinterTest {

    private GoldMinter minter;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        minter = new GoldMinter();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void splitsUnitsIntoIngotsThenLeftoverNuggets() {
        List<ItemStack> stacks = minter.stacksForUnits(23);

        assertEquals(2, stacks.size());
        assertEquals(Material.GOLD_INGOT, stacks.get(0).getType());
        assertEquals(2, stacks.get(0).getAmount());
        assertEquals(Material.GOLD_NUGGET, stacks.get(1).getType());
        assertEquals(5, stacks.get(1).getAmount());
    }

    @Test
    void splitsLargeAmountsAcrossMultipleMaxStacks() {
        // 100 ingots' worth of units = 900 units -> needs two GOLD_INGOT stacks (64 + 36).
        List<ItemStack> stacks = minter.stacksForUnits(100 * CheeseUnits.INGOT_UNITS);

        assertEquals(2, stacks.size());
        assertEquals(64, stacks.get(0).getAmount());
        assertEquals(36, stacks.get(1).getAmount());
    }

    @Test
    void exactIngotAmountProducesNoNuggetStack() {
        List<ItemStack> stacks = minter.stacksForUnits(18);

        assertEquals(1, stacks.size());
        assertEquals(Material.GOLD_INGOT, stacks.get(0).getType());
        assertEquals(2, stacks.get(0).getAmount());
    }

    @Test
    void removeUnitsPrefersNuggetsBeforeBreakingIntoIngots() {
        Inventory inventory = Bukkit.createInventory(null, 9);
        inventory.setItem(0, new ItemStack(Material.GOLD_NUGGET, 4));
        inventory.setItem(1, new ItemStack(Material.GOLD_INGOT, 3));

        long removed = minter.removeUnits(inventory, 4);

        assertEquals(4, removed);
        assertNull(inventory.getItem(0));
        assertEquals(3, inventory.getItem(1).getAmount());
    }

    @Test
    void removeUnitsFallsBackToWholeIngotsForTheRemainder() {
        Inventory inventory = Bukkit.createInventory(null, 9);
        inventory.setItem(0, new ItemStack(Material.GOLD_NUGGET, 4));
        inventory.setItem(1, new ItemStack(Material.GOLD_INGOT, 3));

        // 4 nugget units + 1 whole ingot (9 units) = 13 of the requested 15.
        long removed = minter.removeUnits(inventory, 15);

        assertEquals(13, removed);
        assertNull(inventory.getItem(0));
        assertEquals(2, inventory.getItem(1).getAmount());
    }

    @Test
    void removeUnitsNeverBreaksAnIngotIntoPartialUnits() {
        Inventory inventory = Bukkit.createInventory(null, 9);
        inventory.setItem(0, new ItemStack(Material.GOLD_INGOT, 1));

        // Requesting 5 units can't be satisfied without splitting the one ingot (9 units) — must
        // remove nothing rather than silently rounding.
        long removed = minter.removeUnits(inventory, 5);

        assertEquals(0, removed);
        assertEquals(1, inventory.getItem(0).getAmount());
    }

    @Test
    void removeUnitsCapsAtWhatTheInventoryActuallyHolds() {
        Inventory inventory = Bukkit.createInventory(null, 9);
        inventory.setItem(0, new ItemStack(Material.GOLD_NUGGET, 2));

        long removed = minter.removeUnits(inventory, 100);

        assertEquals(2, removed);
    }
}
