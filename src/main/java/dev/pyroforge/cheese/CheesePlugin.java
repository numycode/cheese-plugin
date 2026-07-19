package dev.pyroforge.cheese;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import dev.pyroforge.cheese.config.CheeseConfig;
import dev.pyroforge.cheese.command.CheeseCommand;
import dev.pyroforge.cheese.creative.CreativeGoldGuard;
import dev.pyroforge.cheese.duplication.DuplicationGuard;
import dev.pyroforge.cheese.economy.GoldCounter;
import dev.pyroforge.cheese.economy.GoldSupplyGate;
import dev.pyroforge.cheese.listener.CreativeInventoryListener;
import dev.pyroforge.cheese.listener.DuplicationGuardListener;
import dev.pyroforge.cheese.scan.FullEconomyScanner;
import dev.pyroforge.cheese.scan.ScanResult;
import dev.pyroforge.cheese.storage.EconomyStorage;
import dev.pyroforge.cheese.survival.GoldCreationListener;
import dev.pyroforge.cheese.survival.GoldDestructionListener;

public class CheesePlugin extends JavaPlugin {

    // How long a "recently released gold" credit stays valid for CreativeGoldGuard, in ticks.
    // Wide enough to cover a single drag/split gesture's sequential packets, narrow enough that
    // it can't be "banked" and spent on an unrelated later grab.
    private static final int CREATIVE_GUARD_DECAY_TICKS = 4;

    private CheeseConfig cheeseConfig;
    private EconomyStorage storage;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.cheeseConfig = new CheeseConfig(getConfig());

        try {
            this.storage = new EconomyStorage(new File(getDataFolder(), cheeseConfig.getStorageFileName()));
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to open the Cheese economy database — disabling.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            if (storage.isSeeded()) {
                getLogger().info("Cheese economy already seeded: currentSupply=" + storage.getCurrentSupply()
                        + " units, maxSupply=" + storage.getMaxSupply() + " units.");
            } else {
                getLogger().info("First run detected — seeding Cheese supply from a full economy scan. "
                        + "This may take a moment on a large world.");
                runSeedScan();
            }
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to read Cheese economy state — disabling.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (cheeseConfig.isBlockGoldFromCreativeMenu()) {
            GoldCounter guardCounter = new GoldCounter(cheeseConfig.getNestedContainerMaxDepth());
            CreativeGoldGuard guard = new CreativeGoldGuard(guardCounter, CREATIVE_GUARD_DECAY_TICKS);
            getServer().getPluginManager().registerEvents(new CreativeInventoryListener(guard), this);
        }

        if (cheeseConfig.isBlockGoldDuplicationTricks()) {
            GoldCounter dupeCounter = new GoldCounter(cheeseConfig.getNestedContainerMaxDepth());
            DuplicationGuard dupeGuard = new DuplicationGuard(dupeCounter);
            getServer().getPluginManager().registerEvents(new DuplicationGuardListener(dupeGuard), this);
        }

        CheeseCommand cheeseCommand = new CheeseCommand(this, cheeseConfig, storage);
        PluginCommand command = getCommand("cheese");
        if (command != null) {
            command.setExecutor(cheeseCommand);
            command.setTabCompleter(cheeseCommand);
        }

        // Survival creation/destruction hooks — cheap insurance on this all-Creative server (see
        // docs/SPEC.md build order step 5), always on since neither is separately toggleable in
        // the config file section of the spec.
        GoldCounter survivalCounter = new GoldCounter(cheeseConfig.getNestedContainerMaxDepth());
        GoldSupplyGate supplyGate = new GoldSupplyGate(storage, survivalCounter);
        getServer().getPluginManager().registerEvents(new GoldCreationListener(supplyGate, getLogger()), this);
        getServer().getPluginManager().registerEvents(
                new GoldDestructionListener(storage, survivalCounter, getLogger()), this);
    }

    private void runSeedScan() {
        List<World> worlds = new ArrayList<>();
        for (String worldName : cheeseConfig.getWorldNames()) {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                getLogger().warning("Configured world '" + worldName + "' is not loaded — skipping it in the seed scan.");
                continue;
            }
            worlds.add(world);
        }

        GoldCounter goldCounter = new GoldCounter(cheeseConfig.getNestedContainerMaxDepth());
        FullEconomyScanner scanner = new FullEconomyScanner(this, goldCounter, cheeseConfig.isIncludePlayerInventoriesInAudit());
        scanner.scan(worlds, this::seedFromScanResult);
    }

    private void seedFromScanResult(ScanResult result) {
        long scannedTotal = result.getTotal();
        // Never seed a cap below what's already circulating — that would put every player
        // instantly over the cap on day one through no fault of their own.
        long maxSupply = Math.max(cheeseConfig.getDefaultMaxSupply(), scannedTotal);
        try {
            storage.seedInitialState(scannedTotal, maxSupply);
            getLogger().info("Seeded Cheese economy: currentSupply=" + scannedTotal + " units, maxSupply="
                    + maxSupply + " units.");
            getLogger().info(result.toReportString());
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to persist the seeded Cheese supply.", e);
        }
    }

    @Override
    public void onDisable() {
        if (storage != null) {
            try {
                storage.close();
            } catch (SQLException e) {
                getLogger().log(Level.WARNING, "Failed to close the Cheese economy database cleanly.", e);
            }
        }
        getLogger().info("Cheese economy plugin disabled.");
    }
}
