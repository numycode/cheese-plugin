package dev.pyroforge.cheese.survival;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
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
 * Covers the FurnaceSmeltEvent and LootGenerateEvent paths end-to-end with real event objects.
 * EntityDeathEvent isn't covered here — constructing a realistic DamageSource isn't worth the
 * risk of testing something that doesn't match a live server; its shared logic (GoldSupplyGate)
 * already has full coverage in GoldSupplyGateTest, and the thin event glue should be checked
 * manually (see class doc). The real event-firing semantics for all three (e.g. whether drops
 * are read before or after other plugins mutate them) still need live-server verification —
 * these tests only prove the listener's own logic is correct given a real event object.
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

    private LootGenerateEvent lootEvent(ItemStack... loot) {
        // A real server always hands LootGenerateEvent a genuinely mutable list — its own
        // setLoot() clears its internal storage in place (confirmed: constructing this event with
        // an immutable List.of(...) makes ANY setLoot() call throw, regardless of listener code),
        // so an immutable list at this point isn't a realistic scenario to simulate.
        World world = server.addSimpleWorld("world");
        Location location = new Location(world, 0, 64, 0);
        LootContext context = new LootContext.Builder(location).build();
        return new LootGenerateEvent(world, null, null, null, context, new ArrayList<>(List.of(loot)), false);
    }

    @Test
    void admitsGeneratedLootThatFitsUnderTheCap() throws Exception {
        storage.seedInitialState(0, 100);
        LootGenerateEvent event = lootEvent(new ItemStack(Material.GOLD_NUGGET, 5));

        listener.onLootGenerate(event);

        assertEquals(5, storage.getCurrentSupply());
    }

    @Test
    void trimsGeneratedLootDownToWhatFitsUnderTheCap() throws Exception {
        storage.seedInitialState(95, 100);
        LootGenerateEvent event = lootEvent(new ItemStack(Material.GOLD_NUGGET, 10));

        listener.onLootGenerate(event);

        assertEquals(100, storage.getCurrentSupply());
        assertEquals(5, event.getLoot().get(0).getAmount());
    }
}
