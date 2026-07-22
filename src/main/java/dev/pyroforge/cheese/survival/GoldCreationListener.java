package dev.pyroforge.cheese.survival;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.PigZombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;

import dev.pyroforge.cheese.economy.GoldSupplyGate;

/**
 * Enforces the Cheese cap on the ways gold enters circulation through survival gameplay — see
 * docs/SPEC.md "Why a scan alone can't enforce the cap". On this all-Creative server these are
 * "cheap insurance" (per docs/SPEC.md's build order) rather than the primary defense, since
 * they'll rarely if ever fire, but they cost little and cover the case of a stray Survival build
 * challenge or a naturally-generated structure.
 *
 * <p>Note the Bukkit API still calls the Zombified Piglin entity type {@link PigZombie} (its
 * legacy pre-1.16 name) even though the in-game entity has been renamed for years — confirmed
 * against the paper-api jar for this build rather than assumed.
 *
 * <p>NOTE: like {@code CreativeInventoryListener}, this is thin event glue around already-tested
 * pure logic ({@link GoldSupplyGate}); the event-firing behavior itself (e.g. whether
 * {@code EntityDeathEvent} drops are read before or after other plugins mutate them) should be
 * checked against a live server, not assumed from the API shape.
 */
public final class GoldCreationListener implements Listener {

    private final GoldSupplyGate gate;
    private final Logger logger;

    public GoldCreationListener(GoldSupplyGate gate, Logger logger) {
        this.gate = gate;
        this.logger = logger;
    }

    @EventHandler
    public void onFurnaceSmelt(FurnaceSmeltEvent event) {
        try {
            if (!gate.admitSingleStack(event.getResult())) {
                event.setCancelled(true);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to record a furnace smelt against the Cheese supply.", e);
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof PigZombie)) {
            return;
        }
        try {
            gate.admitList(event.getDrops());
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to record Zombified Piglin drops against the Cheese supply.", e);
        }
    }

    @EventHandler
    public void onLootGenerate(LootGenerateEvent event) {
        // Copy defensively rather than trimming event.getLoot() in place: admitList() removes
        // entries via Iterator.remove(), which throws UnsupportedOperationException on an
        // immutable list, and nothing guarantees this event hands back a mutable one. Persist the
        // (possibly trimmed) copy back via setLoot() — LootGenerateEvent exposes that alongside
        // getLoot() specifically for this, unlike EntityDeathEvent, which is fine to mutate in
        // place and has no setter at all.
        List<ItemStack> loot = new ArrayList<>(event.getLoot());
        try {
            gate.admitList(loot);
            event.setLoot(loot);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to record generated loot against the Cheese supply.", e);
        }
    }
}
