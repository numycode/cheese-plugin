package dev.pyroforge.cheese.survival;

import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PiglinBarterEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.ItemStack;

import dev.pyroforge.cheese.economy.CraftingGoldDiff;
import dev.pyroforge.cheese.economy.GoldCounter;
import dev.pyroforge.cheese.storage.EconomyStorage;

/**
 * Tracks the ways gold leaves circulation through survival gameplay — see docs/SPEC.md "Why a
 * scan alone can't enforce the cap". Same "cheap insurance on an all-Creative server" framing as
 * {@link GoldCreationListener}, except the crafting-matrix diff here (see
 * {@link CraftingGoldDiff}) is a live vector even in Creative mode: crafting-table recipes still
 * consume their ingredients normally in Creative, unlike the Item Frame/Armor Stand/Decorated Pot
 * placement quirk that {@code DuplicationGuardListener} handles separately.
 */
public final class GoldDestructionListener implements Listener {

    private final EconomyStorage storage;
    private final GoldCounter goldCounter;
    private final CraftingGoldDiff craftingGoldDiff;
    private final Logger logger;

    public GoldDestructionListener(EconomyStorage storage, GoldCounter goldCounter, Logger logger) {
        this.storage = storage;
        this.goldCounter = goldCounter;
        this.craftingGoldDiff = new CraftingGoldDiff(goldCounter);
        this.logger = logger;
    }

    @EventHandler
    public void onPiglinBarter(PiglinBarterEvent event) {
        long units = goldCounter.countItemStack(event.getInput());
        if (units == 0) {
            return;
        }
        try {
            storage.decreaseCurrentSupply(units);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to record a Piglin barter against the Cheese supply.", e);
        }
    }

    @EventHandler
    public void onCraftItem(CraftItemEvent event) {
        ItemStack[] matrix = event.getInventory().getMatrix();
        ItemStack result = event.getInventory().getResult();
        long consumed = craftingGoldDiff.unitsConsumed(matrix, result);
        if (consumed <= 0) {
            return;
        }
        try {
            storage.decreaseCurrentSupply(consumed);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to record gold consumed by a craft against the Cheese supply.", e);
        }
    }
}
