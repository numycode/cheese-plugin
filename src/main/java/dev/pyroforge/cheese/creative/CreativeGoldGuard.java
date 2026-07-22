package dev.pyroforge.cheese.creative;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.inventory.ItemStack;

import dev.pyroforge.cheese.economy.GoldCounter;

/**
 * Decides whether a single {@code InventoryCreativeEvent} slot-change should be blocked as a
 * creative-menu "grab" of gold, versus allowed because it's the player moving gold they already
 * had. See docs/SPEC.md's caveats on this event for why a one-line "cancel if gold" check is
 * wrong — this event fires for in-inventory rearranging too, not just menu grabs.
 *
 * <p>The event only reports "slot X now contains item Y" one slot at a time, so a genuine
 * in-inventory move of a gold stack from slot A to slot B arrives as (at least) two separate
 * events: one that empties/reduces A — a release — and one that fills/increases B — a claim.
 * We track a short-lived per-player pool of recently released gold units; an increase is only
 * allowed if it can be paid for out of that pool. Otherwise it's treated as gold materializing
 * from the creative panel and blocked. The pool decays after {@code decayTicks} of inactivity
 * so it can't be "banked" and spent later.
 *
 * <p>NOTE: this heuristic is reasoned from the event's API shape, not verified against a live
 * server — validate it against docs/SPEC.md's manual test checklist item 2 (real in-inventory
 * drags, stack splits, shift-clicks) before trusting it in production.
 */
public final class CreativeGoldGuard {

    private final GoldCounter goldCounter;
    private final int decayTicks;
    private final Map<UUID, Ledger> ledgers = new HashMap<>();

    public CreativeGoldGuard(GoldCounter goldCounter, int decayTicks) {
        this.goldCounter = goldCounter;
        this.decayTicks = decayTicks;
    }

    /** Returns true if this slot-change should be CANCELLED. */
    public boolean shouldBlock(UUID playerId, ItemStack slotBefore, ItemStack slotAfter, int currentTick) {
        long before = goldCounter.countItemStack(slotBefore);
        long after = goldCounter.countItemStack(slotAfter);
        long delta = after - before;

        if (delta <= 0) {
            if (delta < 0) {
                release(playerId, -delta, currentTick);
            }
            return false;
        }

        Ledger ledger = activeLedger(playerId, currentTick);
        if (ledger != null && ledger.units >= delta) {
            ledger.units -= delta;
            ledger.lastTick = currentTick;
            return false;
        }
        return true;
    }

    private void release(UUID playerId, long units, int currentTick) {
        Ledger ledger = ledgers.computeIfAbsent(playerId, id -> new Ledger());
        if (currentTick - ledger.lastTick > decayTicks) {
            ledger.units = 0;
        }
        ledger.units += units;
        ledger.lastTick = currentTick;
    }

    /** Drops a player's ledger, e.g. on disconnect — nothing worth carrying across sessions. */
    public void forgetPlayer(UUID playerId) {
        ledgers.remove(playerId);
    }

    private Ledger activeLedger(UUID playerId, int currentTick) {
        Ledger ledger = ledgers.get(playerId);
        if (ledger == null) {
            return null;
        }
        if (currentTick - ledger.lastTick > decayTicks) {
            ledgers.remove(playerId);
            return null;
        }
        return ledger;
    }

    private static final class Ledger {
        long units;
        int lastTick;
    }
}
