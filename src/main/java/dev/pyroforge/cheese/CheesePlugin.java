package dev.pyroforge.cheese;

import org.bukkit.plugin.java.JavaPlugin;

public class CheesePlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("Cheese economy plugin enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("Cheese economy plugin disabled.");
    }
}
