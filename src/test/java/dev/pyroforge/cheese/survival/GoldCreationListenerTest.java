package dev.pyroforge.cheese.survival;

import java.io.File;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import dev.pyroforge.cheese.economy.GoldCounter;
import dev.pyroforge.cheese.economy.GoldSupplyGate;
import dev.pyroforge.cheese.storage.EconomyStorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the FurnaceSmeltEvent path end-to-end with a real event object, since its constructor
 * is simple enough to build directly. EntityDeathEvent and LootGenerateEvent aren't covered here
 * — constructing a realistic DamageSource/LootContext isn't worth the risk of testing something
 * that doesn't match a live server; their shared logic (GoldSupplyGate) already has full coverage
 * in GoldSupplyGateTest, and the thin event glue here should be checked manually (see class doc).
 */
class GoldCreationListenerTest {

    @TempDir
    File tempDir;

    private ServerMock server;
    private EconomyStorage storage;
    private GoldCreationListener listener;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        storage = new EconomyStorage(new File(tempDir, "cheese.db"));
        GoldSupplyGate gate = new GoldSupplyGate(storage, new GoldCounter(10));
        listener = new GoldCreationListener(gate, server.getLogger());
    }

    @AfterEach
    void tearDown() throws Exception {
        storage.close();
        MockBukkit.unmock();
    }

    private FurnaceSmeltEvent smeltEvent(ItemStack result) {
        World world = server.addSimpleWorld("world");
        Block block = world.getBlockAt(new Location(world, 0, 64, 0));
        return new FurnaceSmeltEvent(block, new ItemStack(Material.RAW_GOLD, 1), result);
    }

    @Test
    void admitsASmeltThatFitsUnderTheCap() throws Exception {
        storage.seedInitialState(0, 100);
        FurnaceSmeltEvent event = smeltEvent(new ItemStack(Material.GOLD_INGOT, 1));

        listener.onFurnaceSmelt(event);

        assertFalse(event.isCancelled());
        assertEquals(9, storage.getCurrentSupply());
    }

    @Test
    void cancelsASmeltThatWouldExceedTheCap() throws Exception {
        storage.seedInitialState(95, 100);
        FurnaceSmeltEvent event = smeltEvent(new ItemStack(Material.GOLD_INGOT, 1));

        listener.onFurnaceSmelt(event);

        assertTrue(event.isCancelled());
        assertEquals(95, storage.getCurrentSupply());
    }

    @Test
    void ignoresNonGoldSmelts() throws Exception {
        storage.seedInitialState(0, 0);
        FurnaceSmeltEvent event = smeltEvent(new ItemStack(Material.COOKED_BEEF, 1));

        listener.onFurnaceSmelt(event);

        assertFalse(event.isCancelled());
        assertEquals(0, storage.getCurrentSupply());
    }
}
