package dev.pyroforge.cheese.config;

import java.util.List;

import org.bukkit.configuration.file.FileConfiguration;

/** Thin wrapper over config.yml. Only exposes keys that are actually wired to real logic. */
public final class CheeseConfig {

    private final long defaultMaxSupply;
    private final List<String> worldNames;
    private final boolean includePlayerInventoriesInAudit;
    private final int nestedContainerMaxDepth;
    private final String storageFileName;
    private final boolean blockGoldFromCreativeMenu;

    public CheeseConfig(FileConfiguration config) {
        this.defaultMaxSupply = config.getLong("maxSupply", 8100);
        this.worldNames = List.copyOf(config.getStringList("worlds"));
        this.includePlayerInventoriesInAudit = config.getBoolean("includePlayerInventoriesInAudit", true);
        this.nestedContainerMaxDepth = config.getInt("nestedContainerMaxDepth", 10);
        this.storageFileName = config.getString("storage.file", "cheese.db");
        this.blockGoldFromCreativeMenu = config.getBoolean("blockGoldFromCreativeMenu", true);
    }

    public long getDefaultMaxSupply() {
        return defaultMaxSupply;
    }

    public List<String> getWorldNames() {
        return worldNames;
    }

    public boolean isIncludePlayerInventoriesInAudit() {
        return includePlayerInventoriesInAudit;
    }

    public int getNestedContainerMaxDepth() {
        return nestedContainerMaxDepth;
    }

    public String getStorageFileName() {
        return storageFileName;
    }

    public boolean isBlockGoldFromCreativeMenu() {
        return blockGoldFromCreativeMenu;
    }
}
