package dev.pyroforge.cheese.economy;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CraftingGoldDiffTest {

    private CraftingGoldDiff diff;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        diff = new CraftingGoldDiff(new GoldCounter(10));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void countsGoldConsumedByAGoldenAppleCraft() {
        // 8 gold ingots + 1 apple -> 1 golden apple: all 8 ingots (72 units) leave the economy.
        ItemStack[] matrix = new ItemStack[9];
        for (int i = 0; i < 8; i++) {
            matrix[i] = new ItemStack(Material.GOLD_INGOT, 1);
        }
        matrix[8] = new ItemStack(Material.APPLE, 1);
        ItemStack result = new ItemStack(Material.GOLDEN_APPLE, 1);

        assertEquals(72, diff.unitsConsumed(matrix, result));
    }

    @Test
    void isZeroForANonGoldCraft() {
        ItemStack[] matrix = new ItemStack[9];
        matrix[0] = new ItemStack(Material.OAK_PLANKS, 1);
        ItemStack result = new ItemStack(Material.STICK, 4);

        assertEquals(0, diff.unitsConsumed(matrix, result));
    }

    @Test
    void isZeroForIngotsToNuggetsSinceUnitsAreConservedNineToNine() {
        ItemStack[] matrix = new ItemStack[9];
        matrix[0] = new ItemStack(Material.GOLD_INGOT, 1);
        ItemStack result = new ItemStack(Material.GOLD_NUGGET, 9);

        assertEquals(0, diff.unitsConsumed(matrix, result));
    }

    @Test
    void bypassesTheDiffEntirelyWhenCraftingIngotsIntoAGoldBlock() {
        ItemStack[] matrix = new ItemStack[9];
        for (int i = 0; i < 9; i++) {
            matrix[i] = new ItemStack(Material.GOLD_INGOT, 1);
        }
        ItemStack result = new ItemStack(Material.GOLD_BLOCK, 1);

        assertEquals(0, diff.unitsConsumed(matrix, result));
    }

    @Test
    void bypassesTheDiffEntirelyWhenCraftingAGoldBlockBackIntoIngots() {
        ItemStack[] matrix = new ItemStack[9];
        matrix[0] = new ItemStack(Material.GOLD_BLOCK, 1);
        ItemStack result = new ItemStack(Material.GOLD_INGOT, 9);

        assertEquals(0, diff.unitsConsumed(matrix, result));
    }

    @Test
    void handlesAnEmptyMatrixWithoutError() {
        ItemStack[] matrix = new ItemStack[9];
        ItemStack result = new ItemStack(Material.AIR);

        assertEquals(0, diff.unitsConsumed(matrix, result));
    }
}
