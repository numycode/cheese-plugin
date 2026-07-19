package dev.pyroforge.cheese.survival;

import java.io.File;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Piglin;
import org.bukkit.event.entity.PiglinBarterEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import dev.pyroforge.cheese.economy.GoldCounter;
import dev.pyroforge.cheese.storage.EconomyStorage;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the PiglinBarterEvent path end-to-end with a real event object. CraftItemEvent isn't
 * covered here — building a realistic InventoryView/Recipe isn't worth the risk of testing
 * something that doesn't match a live server; its logic (CraftingGoldDiff) already has full
 * coverage in CraftingGoldDiffTest, and the thin event glue should be checked manually.
 */
class GoldDestructionListenerTest {

    @TempDir
    File tempDir;

    private ServerMock server;
    private EconomyStorage storage;
    private GoldDestructionListener listener;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        storage = new EconomyStorage(new File(tempDir, "cheese.db"));
        listener = new GoldDestructionListener(storage, new GoldCounter(10), server.getLogger());
    }

    @AfterEach
    void tearDown() throws Exception {
        storage.close();
        MockBukkit.unmock();
    }

    @Test
    void decreasesSupplyByTheBarteredIngotWithoutTouchingTheCap() throws Exception {
        storage.seedInitialState(50, 8100);
        World world = server.addSimpleWorld("world");
        Piglin piglin = world.spawn(new Location(world, 0, 64, 0), Piglin.class);
        PiglinBarterEvent event = new PiglinBarterEvent(
                piglin, new ItemStack(Material.GOLD_INGOT, 1), List.of(new ItemStack(Material.LEATHER, 1)));

        listener.onPiglinBarter(event);

        assertEquals(41, storage.getCurrentSupply());
        assertEquals(8100, storage.getMaxSupply());
    }

    @Test
    void neverGoesNegativeIfCurrentSupplyIsAlreadyDrifted() throws Exception {
        storage.seedInitialState(5, 8100);
        World world = server.addSimpleWorld("world");
        Piglin piglin = world.spawn(new Location(world, 0, 64, 0), Piglin.class);
        PiglinBarterEvent event = new PiglinBarterEvent(
                piglin, new ItemStack(Material.GOLD_INGOT, 1), List.of());

        listener.onPiglinBarter(event);

        assertEquals(0, storage.getCurrentSupply());
    }
}
