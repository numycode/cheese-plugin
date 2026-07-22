package dev.pyroforge.cheese.duplication;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import dev.pyroforge.cheese.economy.GoldCounter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuplicationGuardTest {

    private static final ItemStack EMPTY = null;

    private DuplicationGuard guard;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        guard = new DuplicationGuard(new GoldCounter(10));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void blocksGoldPlacedIntoAnEmptyItemFrame() {
        ItemStack gold = new ItemStack(Material.GOLD_INGOT, 1);

        assertTrue(guard.blocksItemFramePlacement(EMPTY, gold));
    }

    @Test
    void allowsRotatingAnAlreadyOccupiedItemFrame() {
        ItemStack goldAlreadyInFrame = new ItemStack(Material.GOLD_NUGGET, 1);
        ItemStack heldGold = new ItemStack(Material.GOLD_INGOT, 1);

        assertFalse(guard.blocksItemFramePlacement(goldAlreadyInFrame, heldGold));
    }

    @Test
    void allowsNonGoldIntoAnEmptyItemFrame() {
        ItemStack painting = new ItemStack(Material.PAINTING, 1);

        assertFalse(guard.blocksItemFramePlacement(EMPTY, painting));
    }

    @Test
    void blocksGoldOntoAnArmorStand() {
        ItemStack gold = new ItemStack(Material.GOLD_NUGGET, 1);

        assertTrue(guard.blocksArmorStandPlacement(gold));
    }

    @Test
    void allowsNonGoldOntoAnArmorStand() {
        ItemStack helmet = new ItemStack(Material.IRON_HELMET, 1);

        assertFalse(guard.blocksArmorStandPlacement(helmet));
    }

    @Test
    void allowsTakingAnItemOffAnArmorStand() {
        assertFalse(guard.blocksArmorStandPlacement(EMPTY));
    }

    @Test
    void blocksGoldIntoADecoratedPot() {
        ItemStack gold = new ItemStack(Material.GOLD_INGOT, 1);

        assertTrue(guard.blocksDecoratedPotPlacement(gold));
    }

    @Test
    void allowsNonGoldIntoADecoratedPot() {
        ItemStack stick = new ItemStack(Material.STICK, 1);

        assertFalse(guard.blocksDecoratedPotPlacement(stick));
    }

    @Test
    void blocksGoldHiddenInsideABundle() {
        ItemStack bundle = new ItemStack(Material.BUNDLE, 1);
        BundleMeta meta = (BundleMeta) bundle.getItemMeta();
        meta.addItem(new ItemStack(Material.GOLD_INGOT, 3));
        bundle.setItemMeta(meta);

        assertTrue(guard.blocksItemFramePlacement(EMPTY, bundle));
        assertTrue(guard.blocksArmorStandPlacement(bundle));
        assertTrue(guard.blocksDecoratedPotPlacement(bundle));
    }
}
