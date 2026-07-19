package dev.pyroforge.cheese.economy;

import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Counts raw Gold Ingot/Nugget units in an inventory or item stack, recursing into nested
 * containers (Bundles, and item-form Shulker Boxes via {@link BlockStateMeta}) up to a
 * configured depth. Golden armor/tools and Gold Blocks are intentionally NOT counted — see
 * docs/SPEC.md "Gold Blocks" and "Open items to confirm" sections for why.
 */
public final class GoldCounter {

    private final int maxDepth;

    public GoldCounter(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public long countInventory(Inventory inventory) {
        long total = 0;
        for (ItemStack stack : inventory.getContents()) {
            total += countItemStack(stack, 0);
        }
        return total;
    }

    public long countItemStack(ItemStack stack) {
        return countItemStack(stack, 0);
    }

    private long countItemStack(ItemStack stack, int depth) {
        if (stack == null || stack.getType() == Material.AIR) {
            return 0;
        }

        long total = switch (stack.getType()) {
            case GOLD_INGOT -> CheeseUnits.ingotsToUnits(stack.getAmount());
            case GOLD_NUGGET -> CheeseUnits.nuggetsToUnits(stack.getAmount());
            default -> 0;
        };

        if (depth >= maxDepth || !stack.hasItemMeta()) {
            return total;
        }

        ItemMeta meta = stack.getItemMeta();
        long nested = 0;
        if (meta instanceof BundleMeta bundleMeta) {
            for (ItemStack inner : bundleMeta.getItems()) {
                nested += countItemStack(inner, depth + 1);
            }
        } else if (meta instanceof BlockStateMeta blockStateMeta && blockStateMeta.hasBlockState()) {
            BlockState state = blockStateMeta.getBlockState();
            if (state instanceof InventoryHolder holder) {
                for (ItemStack inner : holder.getInventory().getContents()) {
                    nested += countItemStack(inner, depth + 1);
                }
            }
        }

        // Nested contents scale with stack size (defensive; bundles/filled shulkers are normally
        // unstackable, but this keeps the math correct if that's ever not true).
        return total + (nested * stack.getAmount());
    }
}
