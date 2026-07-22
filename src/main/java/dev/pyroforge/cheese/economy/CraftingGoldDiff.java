package dev.pyroforge.cheese.economy;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Computes how many gold units a single craft consumes, by diffing the crafting matrix against
 * the result rather than enumerating recipes (see docs/SPEC.md "Crafting into derived items" —
 * the recipe list is long and easy to under-count, e.g. golden apple, golden carrot, powered
 * rail, clock, horse armor, golden tools/armor, ...).
 *
 * <p>Gold Blocks are intentionally excluded from both sides of the diff: they're "parked outside
 * the tracked economy" per docs/SPEC.md "Gold Blocks" (the scanner never counts them either), so
 * crafting 9 ingots into a block, or a block back into 9 ingots, must never touch currentSupply
 * in either direction — otherwise one direction looks like a dupe and the other looks like a
 * phantom sink.
 *
 * <p>KNOWN GAP: a shift-clicked craft that produces many items in one client action may still
 * only fire one underlying craft event depending on the server build; this diffs a single craft's
 * matrix/result and would under-count gold consumed by such a batch. Verify against a live server
 * before relying on this for a large stockpile of golden-item crafting; the periodic
 * {@code /cheese audit} will catch any drift this misses in the meantime.
 */
public final class CraftingGoldDiff {

    private final GoldCounter goldCounter;

    public CraftingGoldDiff(GoldCounter goldCounter) {
        this.goldCounter = goldCounter;
    }

    /**
     * Units of gold consumed by one craft of {@code matrix} -> {@code result}. Positive means
     * that many units left the tracked economy (decrement). Zero or negative means nothing should
     * happen — either the craft is gold-neutral, or it's a Gold Block conversion, which always
     * bypasses the cap/count in both directions.
     */
    public long unitsConsumed(ItemStack[] matrix, ItemStack result) {
        if (involvesGoldBlock(matrix, result)) {
            return 0;
        }
        long inputUnits = 0;
        for (ItemStack ingredient : matrix) {
            inputUnits += goldCounter.countItemStack(ingredient);
        }
        long outputUnits = goldCounter.countItemStack(result);
        return inputUnits - outputUnits;
    }

    private boolean involvesGoldBlock(ItemStack[] matrix, ItemStack result) {
        if (result != null && result.getType() == Material.GOLD_BLOCK) {
            return true;
        }
        for (ItemStack stack : matrix) {
            if (stack != null && stack.getType() == Material.GOLD_BLOCK) {
                return true;
            }
        }
        return false;
    }
}
