package dev.pyroforge.cheese.economy;

import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import dev.pyroforge.cheese.storage.EconomyStorage;

/**
 * Enforces the supply cap on gold created through survival gameplay (smelting, mob drops,
 * natural loot generation) — see docs/SPEC.md "Why a scan alone can't enforce the cap". Wraps
 * {@link EconomyStorage}'s cap-check-and-increment with the bookkeeping needed to trim a
 * partially-over-cap batch down to what's actually allowed, rather than an all-or-nothing cancel.
 */
public final class GoldSupplyGate {

    private final EconomyStorage storage;
    private final GoldCounter goldCounter;

    public GoldSupplyGate(EconomyStorage storage, GoldCounter goldCounter) {
        this.storage = storage;
        this.goldCounter = goldCounter;
    }

    /**
     * Single indivisible stack (e.g. one furnace smelt). Returns true if it fit under the cap and
     * was admitted as-is; false if there wasn't room and the caller should cancel the event that
     * would have created it (nothing is persisted in that case).
     */
    public boolean admitSingleStack(ItemStack stack) throws SQLException {
        long units = goldCounter.countItemStack(stack);
        if (units == 0) {
            return true;
        }
        return storage.tryIncreaseCurrentSupplyExact(units);
    }

    /**
     * A mutable batch of stacks (mob drops, generated loot). Trims the gold in {@code items} down
     * to whatever fits under the cap, in place; non-gold items are never touched. Whatever didn't
     * fit is simply not created — it never existed, so nothing needs cancelling elsewhere.
     */
    public void admitList(List<ItemStack> items) throws SQLException {
        long requested = 0;
        for (ItemStack item : items) {
            requested += goldCounter.countItemStack(item);
        }
        if (requested == 0) {
            return;
        }
        long granted = storage.tryIncreaseCurrentSupply(requested);
        if (granted < requested) {
            long actuallyKept = trimGoldTo(items, granted);
            if (actuallyKept < granted) {
                // The grant rounds down to whole items (can't hand out 2/3rds of an ingot), so
                // whatever the grant covered but no item could physically represent must be
                // handed back — otherwise currentSupply silently drifts upward with no matching
                // item in the world.
                storage.decreaseCurrentSupply(granted - actuallyKept);
            }
        }
    }

    /** Trims {@code items}' gold down to {@code allowedUnits}, in place. Returns units actually kept. */
    private long trimGoldTo(List<ItemStack> items, long allowedUnits) {
        long remaining = allowedUnits;
        long kept = 0;
        Iterator<ItemStack> iterator = items.iterator();
        while (iterator.hasNext()) {
            ItemStack stack = iterator.next();
            long unitsPerItem = unitsPerItem(stack == null ? null : stack.getType());
            if (unitsPerItem == 0) {
                continue;
            }
            long stackUnits = unitsPerItem * stack.getAmount();
            if (remaining >= stackUnits) {
                remaining -= stackUnits;
                kept += stackUnits;
                continue;
            }
            long keepableItems = remaining / unitsPerItem;
            long keptUnits = keepableItems * unitsPerItem;
            remaining -= keptUnits;
            kept += keptUnits;
            if (keepableItems <= 0) {
                iterator.remove();
            } else {
                stack.setAmount((int) keepableItems);
            }
        }
        return kept;
    }

    private long unitsPerItem(Material material) {
        if (material == Material.GOLD_INGOT) {
            return CheeseUnits.INGOT_UNITS;
        }
        if (material == Material.GOLD_NUGGET) {
            return CheeseUnits.NUGGET_UNITS;
        }
        return 0;
    }
}
