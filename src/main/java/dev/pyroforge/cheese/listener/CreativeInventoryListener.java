package dev.pyroforge.cheese.listener;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCreativeEvent;

import net.kyori.adventure.text.Component;

import dev.pyroforge.cheese.creative.CreativeGoldGuard;

/**
 * Blocks grabbing Gold Ingots/Nuggets from the Creative inventory menu, without blocking a
 * player from rearranging gold they already legitimately hold (see {@link CreativeGoldGuard}
 * for how that distinction is made). Also covers middle-click pick-block, IF that action
 * routes through {@code InventoryCreativeEvent} on this server build — confirm via the manual
 * test checklist in docs/SPEC.md; if it doesn't, pick-block is an open bypass needing its own
 * handler.
 */
public final class CreativeInventoryListener implements Listener {

    private static final int MESSAGE_RATE_LIMIT_TICKS = 20; // one action-bar message per second

    private final CreativeGoldGuard guard;
    private final Map<UUID, Integer> lastMessageTick = new HashMap<>();

    public CreativeInventoryListener(CreativeGoldGuard guard) {
        this.guard = guard;
    }

    @EventHandler
    public void onCreativeInventoryClick(InventoryCreativeEvent event) {
        HumanEntity who = event.getWhoClicked();
        if (!(who instanceof Player player)) {
            return;
        }

        boolean blocked = guard.shouldBlock(
                player.getUniqueId(), event.getCurrentItem(), event.getCursor(), Bukkit.getCurrentTick());
        if (!blocked) {
            return;
        }

        event.setCancelled(true);
        // Cancelling InventoryCreativeEvent doesn't reliably re-sync the client's own local
        // inventory model, since creative mode is client-authoritative; force it explicitly.
        player.updateInventory();
        maybeWarn(player);
    }

    private void maybeWarn(Player player) {
        int currentTick = Bukkit.getCurrentTick();
        Integer last = lastMessageTick.get(player.getUniqueId());
        if (last != null && currentTick - last < MESSAGE_RATE_LIMIT_TICKS) {
            return;
        }
        lastMessageTick.put(player.getUniqueId(), currentTick);
        player.sendActionBar(Component.text("Gold Ingots/Nuggets are managed by /cheese — ask an admin"));
    }
}
