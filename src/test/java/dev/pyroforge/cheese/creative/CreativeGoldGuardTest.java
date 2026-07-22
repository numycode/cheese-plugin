package dev.pyroforge.cheese.creative;

import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import dev.pyroforge.cheese.economy.GoldCounter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreativeGoldGuardTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final ItemStack EMPTY = null;

    private CreativeGoldGuard guard;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        guard = new CreativeGoldGuard(new GoldCounter(10), 4);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void blocksFreshGoldAppearingWithNoPriorRelease() {
        ItemStack gold = new ItemStack(Material.GOLD_INGOT, 5);

        assertTrue(guard.shouldBlock(PLAYER, EMPTY, gold, 100));
    }

    @Test
    void allowsMovingAnExistingStackBetweenSlots() {
        ItemStack gold = new ItemStack(Material.GOLD_INGOT, 5);

        // Event 1: source slot cleared (release).
        assertFalse(guard.shouldBlock(PLAYER, gold, EMPTY, 100));
        // Event 2: destination slot filled with the same amount, same gesture.
        assertFalse(guard.shouldBlock(PLAYER, EMPTY, gold, 101));
    }

    @Test
    void allowsSplittingAStackAcrossTwoSlots() {
        ItemStack whole = new ItemStack(Material.GOLD_NUGGET, 10);
        ItemStack half = new ItemStack(Material.GOLD_NUGGET, 5);

        // Source slot reduced from 10 to 5 (releases 5).
        assertFalse(guard.shouldBlock(PLAYER, whole, half, 100));
        // Destination slot gains the other 5.
        assertFalse(guard.shouldBlock(PLAYER, EMPTY, half, 100));
    }

    @Test
    void blocksClaimingMoreThanWasReleased() {
        ItemStack small = new ItemStack(Material.GOLD_NUGGET, 3);
        ItemStack large = new ItemStack(Material.GOLD_INGOT, 5);

        assertFalse(guard.shouldBlock(PLAYER, small, EMPTY, 100));
        assertTrue(guard.shouldBlock(PLAYER, EMPTY, large, 100));
    }

    @Test
    void releaseCreditExpiresAfterDecayWindow() {
        ItemStack gold = new ItemStack(Material.GOLD_INGOT, 5);

        assertFalse(guard.shouldBlock(PLAYER, gold, EMPTY, 100));
        // Well past the 4-tick decay window.
        assertTrue(guard.shouldBlock(PLAYER, EMPTY, gold, 200));
    }

    @Test
    void neverBlocksNonGoldItems() {
        ItemStack diamonds = new ItemStack(Material.DIAMOND, 64);

        assertFalse(guard.shouldBlock(PLAYER, EMPTY, diamonds, 100));
    }

    @Test
    void separatePlayersDoNotShareAReleaseCredit() {
        UUID otherPlayer = UUID.randomUUID();
        ItemStack gold = new ItemStack(Material.GOLD_INGOT, 5);

        assertFalse(guard.shouldBlock(PLAYER, gold, EMPTY, 100));
        assertTrue(guard.shouldBlock(otherPlayer, EMPTY, gold, 100));
    }

    @Test
    void forgetPlayerDropsAnyOutstandingReleaseCredit() {
        ItemStack gold = new ItemStack(Material.GOLD_INGOT, 5);
        assertFalse(guard.shouldBlock(PLAYER, gold, EMPTY, 100));

        guard.forgetPlayer(PLAYER);

        // The credit from the release above is gone, so this now looks like a fresh grab.
        assertTrue(guard.shouldBlock(PLAYER, EMPTY, gold, 101));
    }

    @Test
    void forgetPlayerIsSafeForAPlayerWithNoLedger() {
        guard.forgetPlayer(UUID.randomUUID());
    }
}
