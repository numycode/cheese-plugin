package dev.pyroforge.cheese.economy;

import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GoldCounterTest {

    private GoldCounter counter;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        counter = new GoldCounter(10);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void countsPlainIngotStack() {
        ItemStack stack = new ItemStack(Material.GOLD_INGOT, 3);
        assertEquals(27, counter.countItemStack(stack));
    }

    @Test
    void countsPlainNuggetStack() {
        ItemStack stack = new ItemStack(Material.GOLD_NUGGET, 5);
        assertEquals(5, counter.countItemStack(stack));
    }

    @Test
    void ignoresNonGoldItems() {
        ItemStack stack = new ItemStack(Material.DIAMOND, 64);
        assertEquals(0, counter.countItemStack(stack));
    }

    @Test
    void ignoresGoldenEquipmentNotJustRawMaterials() {
        ItemStack sword = new ItemStack(Material.GOLDEN_SWORD, 1);
        ItemStack block = new ItemStack(Material.GOLD_BLOCK, 1);
        assertEquals(0, counter.countItemStack(sword));
        assertEquals(0, counter.countItemStack(block));
    }

    @Test
    void countsGoldNestedInBundle() {
        ItemStack bundle = new ItemStack(Material.BUNDLE);
        BundleMeta meta = (BundleMeta) bundle.getItemMeta();
        meta.addItem(new ItemStack(Material.GOLD_INGOT, 2));
        meta.addItem(new ItemStack(Material.GOLD_NUGGET, 4));
        bundle.setItemMeta(meta);

        assertEquals(2 * CheeseUnits.INGOT_UNITS + 4, counter.countItemStack(bundle));
    }

    @Test
    void countsGoldNestedInShulkerBoxItem() {
        ItemStack shulker = new ItemStack(Material.SHULKER_BOX);
        BlockStateMeta meta = (BlockStateMeta) shulker.getItemMeta();
        ShulkerBox box = (ShulkerBox) meta.getBlockState();
        box.getInventory().addItem(new ItemStack(Material.GOLD_INGOT, 1));
        meta.setBlockState(box);
        shulker.setItemMeta(meta);

        assertEquals(CheeseUnits.INGOT_UNITS, counter.countItemStack(shulker));
    }

    @Test
    void respectsMaxRecursionDepth() {
        GoldCounter shallowCounter = new GoldCounter(0);
        ItemStack bundle = new ItemStack(Material.BUNDLE);
        BundleMeta meta = (BundleMeta) bundle.getItemMeta();
        meta.addItem(new ItemStack(Material.GOLD_INGOT, 1));
        bundle.setItemMeta(meta);

        assertEquals(0, shallowCounter.countItemStack(bundle));
    }
}
