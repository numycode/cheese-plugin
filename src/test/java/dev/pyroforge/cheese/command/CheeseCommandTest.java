package dev.pyroforge.cheese.command;

import java.io.File;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import dev.pyroforge.cheese.CheesePlugin;
import dev.pyroforge.cheese.config.CheeseConfig;
import dev.pyroforge.cheese.storage.EconomyStorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link CheeseCommand} against real permission checks and a real (temp-file) SQLite
 * {@link EconomyStorage}. Uses its own storage instance rather than {@code CheesePlugin}'s
 * internal one, so these tests don't depend on the plugin's async first-run seed scan having
 * finished (see docs/SPEC.md's manual test checklist for that part instead).
 */
class CheeseCommandTest {

    @TempDir
    File tempDir;

    private ServerMock server;
    private CheesePlugin plugin;
    private EconomyStorage storage;
    private CheeseCommand command;
    private PlayerMock player;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(CheesePlugin.class);
        storage = new EconomyStorage(new File(tempDir, "test.db"));
        storage.seedInitialState(150, 8100);
        CheeseConfig config = new CheeseConfig(new YamlConfiguration());
        command = new CheeseCommand(plugin, config, storage);
        player = server.addPlayer("Tester");
    }

    @AfterEach
    void tearDown() throws Exception {
        storage.close();
        MockBukkit.unmock();
    }

    private void grantAdmin() {
        player.addAttachment(plugin, "cheese.admin", true);
    }

    @Test
    void capGetIsAllowedByDefault() {
        command.onCommand(player, null, "cheese", new String[] {"cap", "get"});

        assertTrue(player.nextMessage().contains("150"));
    }

    @Test
    void capSetIsDeniedWithoutAdminPermission() throws Exception {
        command.onCommand(player, null, "cheese", new String[] {"cap", "set", "500"});

        assertEquals("You don't have permission to do that.", player.nextMessage());
        assertEquals(8100, storage.getMaxSupply());
    }

    @Test
    void capSetSucceedsWithAdminPermission() throws Exception {
        grantAdmin();

        command.onCommand(player, null, "cheese", new String[] {"cap", "set", "500"});

        assertEquals(500, storage.getMaxSupply());
    }

    @Test
    void addMintsGoldToTheTargetPlayerAndRaisesTheCap() throws Exception {
        grantAdmin();

        command.onCommand(player, null, "cheese", new String[] {"add", "20", "Tester"});

        assertEquals(170, storage.getCurrentSupply());
        assertEquals(8120, storage.getMaxSupply());
        long units = countGoldUnits(player.getInventory().getContents());
        assertEquals(20, units);
    }

    @Test
    void addIsDeniedWithoutAdminPermission() throws Exception {
        command.onCommand(player, null, "cheese", new String[] {"add", "20", "Tester"});

        assertEquals("You don't have permission to do that.", player.nextMessage());
        assertEquals(150, storage.getCurrentSupply());
    }

    @Test
    void removeDestroysGoldFromTheTargetPlayerAndLowersTheCap() throws Exception {
        grantAdmin();
        player.getInventory().addItem(new ItemStack(Material.GOLD_NUGGET, 10));

        command.onCommand(player, null, "cheese", new String[] {"remove", "10", "Tester"});

        assertEquals(140, storage.getCurrentSupply());
        assertEquals(8090, storage.getMaxSupply());
        assertEquals(0, countGoldUnits(player.getInventory().getContents()));
    }

    @Test
    void removeReportsAnUnknownOfflinePlayerRatherThanGuessing() throws Exception {
        grantAdmin();

        command.onCommand(player, null, "cheese", new String[] {"remove", "10", "NobodyOnline"});

        assertTrue(player.nextMessage().contains("not online"));
        assertEquals(150, storage.getCurrentSupply());
    }

    @Test
    void scanFullIsDeniedWithoutAdminPermission() {
        command.onCommand(player, null, "cheese", new String[] {"scan", "--full"});

        assertEquals("You don't have permission to do that.", player.nextMessage());
    }

    @Test
    void auditIsDeniedWithoutAdminPermission() {
        command.onCommand(player, null, "cheese", new String[] {"audit"});

        assertEquals("You don't have permission to do that.", player.nextMessage());
    }

    @Test
    void scanLoadedIsAllowedByDefaultAndReturnsAReport() {
        command.onCommand(player, null, "cheese", new String[] {"scan"});

        assertTrue(player.nextMessage().contains("Cheese scan"));
    }

    @Test
    void consoleBypassesCheeseAdminEvenWithoutBeingGrantedIt() throws Exception {
        CommandSender console = server.getConsoleSender();

        command.onCommand(console, null, "cheese", new String[] {"cap", "set", "500"});

        assertEquals(500, storage.getMaxSupply());
    }

    @Test
    void opStatusAloneDoesNotGrantCheeseAdmin() throws Exception {
        player.setOp(true);

        command.onCommand(player, null, "cheese", new String[] {"cap", "set", "500"});

        assertEquals("You don't have permission to do that.", player.nextMessage());
        assertEquals(8100, storage.getMaxSupply());
    }

    private long countGoldUnits(ItemStack[] contents) {
        long units = 0;
        for (ItemStack stack : contents) {
            if (stack == null) {
                continue;
            }
            if (stack.getType() == Material.GOLD_INGOT) {
                units += stack.getAmount() * 9L;
            } else if (stack.getType() == Material.GOLD_NUGGET) {
                units += stack.getAmount();
            }
        }
        return units;
    }
}
