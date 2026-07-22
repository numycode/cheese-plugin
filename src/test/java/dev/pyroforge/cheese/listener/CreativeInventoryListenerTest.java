package dev.pyroforge.cheese.listener;

import org.bukkit.Material;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerQuitEvent.QuitReason;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import net.kyori.adventure.text.Component;

import dev.pyroforge.cheese.creative.CreativeGoldGuard;
import dev.pyroforge.cheese.economy.GoldCounter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the quit-cleanup wiring specifically, since that's the part not already exercised by
 * {@code CreativeGoldGuardTest} — dispatched through the real Bukkit event bus (not a direct
 * method call), since {@code @EventHandler} registration is exactly what's under test here.
 */
class CreativeInventoryListenerTest {

    private static final ItemStack EMPTY = null;

    private ServerMock server;
    private CreativeGoldGuard guard;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        guard = new CreativeGoldGuard(new GoldCounter(10), 4);
        PluginMock plugin = MockBukkit.createMockPlugin();
        server.getPluginManager().registerEvents(new CreativeInventoryListener(guard), plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void quittingDropsThePlayersOutstandingReleaseCredit() {
        PlayerMock player = server.addPlayer("Tester");
        ItemStack gold = new ItemStack(Material.GOLD_INGOT, 5);
        // Release a credit directly on the guard (no need to construct InventoryCreativeEvent,
        // which needs a real open InventoryView) — what's under test is whether quitting clears it.
        assertFalse(guard.shouldBlock(player.getUniqueId(), gold, EMPTY, 100));

        server.getPluginManager().callEvent(
                new PlayerQuitEvent(player, Component.text("Tester left"), QuitReason.DISCONNECTED));

        // The credit from the release above should be gone, so this looks like a fresh grab.
        assertTrue(guard.shouldBlock(player.getUniqueId(), EMPTY, gold, 101));
    }
}
