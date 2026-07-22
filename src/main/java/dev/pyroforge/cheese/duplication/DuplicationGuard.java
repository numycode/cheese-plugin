package dev.pyroforge.cheese.duplication;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import dev.pyroforge.cheese.economy.GoldCounter;

/**
 * Decides whether placing a held item into an Item Frame, onto an Armor Stand, or into a
 * Decorated Pot should be blocked. See docs/SPEC.md "Creative mode's built-in 'no item
 * consumption' behavior" — in Creative, placing an item into one of these holders does not
 * remove it from the player's hand, so a single legitimately-granted Gold Ingot/Nugget could
 * otherwise be turned into unlimited gold.
 *
 * <p>Uses {@link GoldCounter} rather than a bare material check, so gold hidden inside a Bundle
 * or a filled Shulker Box is caught too, not just bare ingots/nuggets.
 */
public final class DuplicationGuard {

    private final GoldCounter goldCounter;

    public DuplicationGuard(GoldCounter goldCounter) {
        this.goldCounter = goldCounter;
    }

    /**
     * Only placing into an EMPTY frame duplicates anything — clicking an already-occupied frame
     * just rotates its contents and must stay allowed.
     */
    public boolean blocksItemFramePlacement(ItemStack currentFrameItem, ItemStack heldItem) {
        return isEmpty(currentFrameItem) && isGold(heldItem);
    }

    public boolean blocksArmorStandPlacement(ItemStack heldItem) {
        return isGold(heldItem);
    }

    public boolean blocksDecoratedPotPlacement(ItemStack heldItem) {
        return isGold(heldItem);
    }

    private boolean isGold(ItemStack stack) {
        return goldCounter.countItemStack(stack) > 0;
    }

    private boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR;
    }
}
