package dev.pyroforge.cheese.listener;

import org.bukkit.Material;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import dev.pyroforge.cheese.duplication.DuplicationGuard;

/**
 * Blocks the three Creative "placing doesn't consume the item" dupe vectors for Gold
 * Ingots/Nuggets: Item Frame (and Glow Item Frame, which extends {@link ItemFrame}), Armor
 * Stand equipment slots, and Decorated Pots. See {@link DuplicationGuard} for the actual
 * gold/empty checks and docs/SPEC.md for why these three specifically matter on an all-Creative
 * server.
 */
public final class DuplicationGuardListener implements Listener {

    private final DuplicationGuard guard;

    public DuplicationGuardListener(DuplicationGuard guard) {
        this.guard = guard;
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame frame)) {
            return;
        }
        ItemStack held = itemInHand(event.getPlayer(), event.getHand());
        if (guard.blocksItemFramePlacement(frame.getItem(), held)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (guard.blocksArmorStandPlacement(event.getPlayerItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        if (event.getClickedBlock().getType() != Material.DECORATED_POT) {
            return;
        }
        ItemStack held = itemInHand(event.getPlayer(), event.getHand());
        if (guard.blocksDecoratedPotPlacement(held)) {
            event.setCancelled(true);
        }
    }

    private ItemStack itemInHand(Player player, EquipmentSlot hand) {
        if (hand == EquipmentSlot.OFF_HAND) {
            return player.getInventory().getItemInOffHand();
        }
        return player.getInventory().getItemInMainHand();
    }
}
