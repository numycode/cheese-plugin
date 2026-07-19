package dev.pyroforge.cheese;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import dev.pyroforge.cheese.config.CheeseConfig;
import dev.pyroforge.cheese.economy.GoldCounter;
import dev.pyroforge.cheese.scan.FullEconomyScanner;
import dev.pyroforge.cheese.scan.ScanResult;
import dev.pyroforge.cheese.storage.EconomyStorage;

public class CheesePlugin extends JavaPlugin {

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
        }
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
