package dev.pyroforge.cheese.economy;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

import dev.pyroforge.cheese.storage.EconomyStorage;

/**
 * Enforces the supply cap on gold created through survival gameplay (smelting, mob drops,
 * natural loot generation) — see docs/SPEC.md "Why a scan alone can't enforce the cap". Wraps
 * {@link EconomyStorage}'s cap-check-and-increment with the bookkeeping needed to trim a
 * partially-over-cap batch down to what's actually allowed, rather than an all-or-nothing cancel.
 *
 * <p>Trimming recurses into nested containers (Bundles, item-form Shulker Boxes) with the same
 * traversal {@link GoldCounter} uses to count them, up to the same depth cap — so gold hidden
 * inside one of these can't slip past admission accounting by being packed into a mob drop.
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
        return trimList(items, allowedUnits, 0);
    }

    /**
     * Trims gold across a plain list of stacks (a top-level batch, or a Bundle's contents) down to
     * {@code allowedUnits}, in place. A bare gold stack with none of it admitted is removed
     * entirely (it IS the gold, nothing else to preserve); a container stack (Bundle/Shulker)
     * whose gold is fully rejected is only removed if that leaves it completely empty — if it
     * still holds non-gold items, it's kept with just its gold portion stripped out. A stack with
     * no gold at all is never touched.
     */
    private long trimList(List<ItemStack> stacks, long allowedUnits, int depth) {
        long remaining = allowedUnits;
        long kept = 0;
        Iterator<ItemStack> iterator = stacks.iterator();
        while (iterator.hasNext()) {
            ItemStack stack = iterator.next();
            long stackTotal = goldCounter.countItemStack(stack);
            if (stackTotal == 0) {
                continue;
            }
            long stackKept = trimStack(stack, Math.max(0, remaining), depth);
            remaining -= stackKept;
            kept += stackKept;
            if (isNowEmpty(stack, stackKept)) {
                iterator.remove();
            }
        }
        return kept;
    }

    /**
     * Trims gold within a fixed-slot {@link Inventory} (a filled Shulker Box's contents) down to
     * {@code allowedUnits}, preserving slot indices — nulling out a slot only when it ends up
     * completely empty (see {@link #trimList}), rather than shifting the ones after it.
     */
    private long trimInventory(Inventory inventory, long allowedUnits, int depth) {
        long remaining = allowedUnits;
        long kept = 0;
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            long stackTotal = goldCounter.countItemStack(stack);
            if (stackTotal == 0) {
                continue;
            }
            long stackKept = trimStack(stack, Math.max(0, remaining), depth);
            remaining -= stackKept;
            kept += stackKept;
            inventory.setItem(slot, isNowEmpty(stack, stackKept) ? null : stack);
        }
        return kept;
    }

    /**
     * Whether {@code stack} (which had {@code > 0} gold units before trimming) should disappear
     * entirely now. A bare gold stack is its own gold, so 0 kept means nothing is left of it. A
     * container is only "empty" once it holds nothing at all, gold or otherwise — its non-gold
     * contents must survive even when all of its gold was rejected.
     */
    private boolean isNowEmpty(ItemStack stack, long stackKept) {
        Material type = stack.getType();
        if (type == Material.GOLD_INGOT || type == Material.GOLD_NUGGET) {
            return stackKept == 0;
        }
        if (!stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof BundleMeta bundleMeta) {
            return bundleMeta.getItems().isEmpty();
        }
        if (meta instanceof BlockStateMeta blockStateMeta && blockStateMeta.hasBlockState()
                && blockStateMeta.getBlockState() instanceof InventoryHolder holder) {
            for (ItemStack inner : holder.getInventory().getContents()) {
                if (inner != null && inner.getType() != Material.AIR) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Trims a single stack (already known to hold {@code > 0} gold units) down to {@code allowed},
     * mutating it in place for a partial keep. Returns units actually kept — the caller decides
     * whether the stack itself should now disappear (see {@link #isNowEmpty}).
     */
    private long trimStack(ItemStack stack, long allowed, int depth) {
        // No early-return shortcut for allowed <= 0: a container (Bundle/Shulker) still needs to
        // recurse in that case so its own gold gets stripped out in place, even though nothing of
        // it will be kept — skipping the recursion would leave the rejected gold sitting
        // untouched inside a container that isNowEmpty() then wrongly judges as non-empty.
        Material type = stack.getType();
        if (type == Material.GOLD_INGOT || type == Material.GOLD_NUGGET) {
            long unitsPerItem = type == Material.GOLD_INGOT ? CheeseUnits.INGOT_UNITS : CheeseUnits.NUGGET_UNITS;
            long keepableItems = Math.min(stack.getAmount(), allowed / unitsPerItem);
            if (keepableItems <= 0) {
                return 0;
            }
            if (keepableItems < stack.getAmount()) {
                stack.setAmount((int) keepableItems);
            }
            return keepableItems * unitsPerItem;
        }

        if (depth >= goldCounter.getMaxDepth() || !stack.hasItemMeta()) {
            return 0;
        }

        // Bundles/filled Shulkers are always unstackable in vanilla (max stack size 1), so unlike
        // GoldCounter's defensive "nested * stack.getAmount()" this doesn't need to scale by
        // stack.getAmount() — there's only ever one such container per stack in practice.
        ItemMeta meta = stack.getItemMeta();
        long kept = 0;
        if (meta instanceof BundleMeta bundleMeta) {
            List<ItemStack> innerItems = new ArrayList<>(bundleMeta.getItems());
            kept = trimList(innerItems, allowed, depth + 1);
            bundleMeta.setItems(innerItems);
            stack.setItemMeta(meta);
        } else if (meta instanceof BlockStateMeta blockStateMeta && blockStateMeta.hasBlockState()) {
            BlockState state = blockStateMeta.getBlockState();
            if (state instanceof InventoryHolder holder) {
                kept = trimInventory(holder.getInventory(), allowed, depth + 1);
                blockStateMeta.setBlockState(state);
                stack.setItemMeta(meta);
            }
        }
        return kept;
    }
}
