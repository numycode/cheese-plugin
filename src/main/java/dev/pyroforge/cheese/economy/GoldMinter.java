package dev.pyroforge.cheese.economy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Turns a raw unit amount into physical Gold Ingot/Nugget stacks (and back), for {@code /cheese
 * add}/{@code /cheese remove}. Mints/removes ItemStacks directly rather than going through
 * inventory-click simulation, so {@code CreativeGoldGuard} never sees these as a creative-menu
 * grab (see docs/SPEC.md "Note on /cheese add minting").
 */
public final class GoldMinter {

    private static final int MAX_STACK_SIZE = 64;

    /** Splits {@code units} into the fewest ingot/nugget stacks, each capped at a max stack size. */
    public List<ItemStack> stacksForUnits(long units) {
        List<ItemStack> stacks = new ArrayList<>();
        long ingots = units / CheeseUnits.INGOT_UNITS;
        long leftoverNuggets = units % CheeseUnits.INGOT_UNITS;
        appendStacks(stacks, Material.GOLD_INGOT, ingots);
        appendStacks(stacks, Material.GOLD_NUGGET, leftoverNuggets);
        return stacks;
    }

    private void appendStacks(List<ItemStack> stacks, Material material, long amount) {
        while (amount > 0) {
            int stackAmount = (int) Math.min(amount, MAX_STACK_SIZE);
            stacks.add(new ItemStack(material, stackAmount));
            amount -= stackAmount;
        }
    }

    /** Gives {@code units} worth of gold to the player; whatever doesn't fit is dropped at their feet. */
    public void giveTo(HumanEntity player, long units) {
        for (ItemStack stack : stacksForUnits(units)) {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            for (ItemStack notFit : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), notFit);
            }
        }
    }

    /** Drops {@code units} worth of gold at a world location. */
    public void dropAt(Location location, long units) {
        for (ItemStack stack : stacksForUnits(units)) {
            location.getWorld().dropItemNaturally(location, stack);
        }
    }

    /**
     * Removes up to {@code units} worth of gold from the inventory: nuggets first (removable in
     * any partial amount), then whole ingots for whatever remains. An ingot can't be partially
     * removed without a craft the player didn't ask for, so if what's left after nuggets isn't a
     * multiple of {@value CheeseUnits#INGOT_UNITS}, the remainder is left in place. Returns the
     * amount actually removed, which callers must use (not the requested amount) when lowering
     * the tracked supply/cap.
     */
    public long removeUnits(Inventory inventory, long units) {
        long removed = removeWholeItems(inventory, Material.GOLD_NUGGET, CheeseUnits.NUGGET_UNITS, units);
        long stillNeeded = units - removed;
        removed += removeWholeItems(inventory, Material.GOLD_INGOT, CheeseUnits.INGOT_UNITS, stillNeeded);
        return removed;
    }

    private long removeWholeItems(Inventory inventory, Material material, long unitsPerItem, long targetUnits) {
        long itemsNeeded = targetUnits / unitsPerItem;
        if (itemsNeeded <= 0) {
            return 0;
        }
        long itemsRemoved = 0;
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length && itemsRemoved < itemsNeeded; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType() != material) {
                continue;
            }
            long take = Math.min(stack.getAmount(), itemsNeeded - itemsRemoved);
            itemsRemoved += take;
            int newAmount = (int) (stack.getAmount() - take);
            inventory.setItem(slot, newAmount <= 0 ? null : withAmount(stack, newAmount));
        }
        return itemsRemoved * unitsPerItem;
    }

    private ItemStack withAmount(ItemStack stack, int amount) {
        ItemStack copy = stack.clone();
        copy.setAmount(amount);
        return copy;
    }
}
