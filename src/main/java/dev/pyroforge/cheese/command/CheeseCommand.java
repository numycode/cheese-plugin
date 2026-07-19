package dev.pyroforge.cheese.command;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import dev.pyroforge.cheese.config.CheeseConfig;
import dev.pyroforge.cheese.economy.CheeseUnits;
import dev.pyroforge.cheese.economy.GoldCounter;
import dev.pyroforge.cheese.economy.GoldMinter;
import dev.pyroforge.cheese.scan.FullEconomyScanner;
import dev.pyroforge.cheese.scan.ScanResult;
import dev.pyroforge.cheese.storage.EconomyStorage;

/**
 * Handles {@code /cheese scan|cap|add|remove|audit}. Permission split per docs/SPEC.md "Safety
 * and permissions": {@code cheese.use} (default true) covers {@code scan --loaded} and
 * {@code cap get}; everything else — {@code scan --full}, {@code cap set}, {@code add},
 * {@code remove}, {@code audit} — requires {@code cheese.admin} (default false), checked via
 * {@code hasPermission}, never {@code isOp()} (everyone on this server is OP, so OP status alone
 * must never imply cheese.admin). The server console is the one deliberate exception to that
 * check — see {@link #hasPermission(CommandSender, String)} — since whoever has console/RCON
 * access already has full control over the server (including the SQLite file directly), so
 * gating it behind an in-game permission node adds no real security, only friction.
 */
public final class CheeseCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("scan", "cap", "add", "remove", "audit");
    private static final double REMOVE_AT_LOCATION_RADIUS = 3.0;

    private final Plugin plugin;
    private final CheeseConfig config;
    private final EconomyStorage storage;
    private final GoldMinter minter = new GoldMinter();

    private long lastFullScanMillis = 0;

    public CheeseCommand(Plugin plugin, CheeseConfig config, EconomyStorage storage) {
        this.plugin = plugin;
        this.config = config;
        this.storage = storage;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /cheese <scan|cap|add|remove|audit>");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "scan" -> handleScan(sender, args);
            case "cap" -> handleCap(sender, args);
            case "add" -> handleMint(sender, args, true);
            case "remove" -> handleMint(sender, args, false);
            case "audit" -> handleAudit(sender);
            default -> sender.sendMessage("Unknown /cheese subcommand: " + args[0]);
        }
        return true;
    }

    // ---- scan ----

    private void handleScan(CommandSender sender, String[] args) {
        String mode = args.length > 1 ? args[1] : "--loaded";
        if (!mode.equalsIgnoreCase("--loaded") && !mode.equalsIgnoreCase("--full")) {
            sender.sendMessage("Usage: /cheese scan [--loaded|--full]");
            return;
        }

        if (mode.equalsIgnoreCase("--full")) {
            if (!hasPermission(sender, "cheese.admin")) {
                deny(sender);
                return;
            }
            if (!checkAndUpdateCooldown(sender)) {
                return;
            }
            sender.sendMessage("Starting a full Cheese scan — this may take a moment...");
            newScanner().scan(resolveWorlds(), result -> sender.sendMessage(result.toReportString()));
        } else {
            if (!hasPermission(sender, "cheese.use")) {
                deny(sender);
                return;
            }
            ScanResult result = newScanner().scanLoaded(resolveWorlds());
            sender.sendMessage(result.toReportString());
        }
    }

    // ---- cap ----

    private void handleCap(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /cheese cap <get|set>");
            return;
        }
        if (args[1].equalsIgnoreCase("get")) {
            if (!hasPermission(sender, "cheese.use")) {
                deny(sender);
                return;
            }
            try {
                sender.sendMessage("Cheese supply: " + storage.getCurrentSupply() + " / " + storage.getMaxSupply()
                        + " units.");
            } catch (SQLException e) {
                fail(sender, "read the cap", e);
            }
        } else if (args[1].equalsIgnoreCase("set")) {
            if (!hasPermission(sender, "cheese.admin")) {
                deny(sender);
                return;
            }
            if (args.length < 3) {
                sender.sendMessage("Usage: /cheese cap set <units>");
                return;
            }
            Long units = parseUnits(sender, args[2]);
            if (units == null) {
                return;
            }
            try {
                storage.setMaxSupply(units);
                sender.sendMessage("Cheese cap set to " + units + " units.");
                plugin.getLogger().info(sender.getName() + " set the Cheese cap to " + units + " units.");
            } catch (SQLException e) {
                fail(sender, "set the cap", e);
            }
        } else {
            sender.sendMessage("Usage: /cheese cap <get|set>");
        }
    }

    // ---- add / remove ----

    private void handleMint(CommandSender sender, String[] args, boolean isAdd) {
        if (!hasPermission(sender, "cheese.admin")) {
            deny(sender);
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("Usage: /cheese " + (isAdd ? "add" : "remove") + " <units> <player|x y z>");
            return;
        }
        Long units = parseUnits(sender, args[1]);
        if (units == null) {
            return;
        }

        Player targetPlayer = Bukkit.getPlayerExact(args[2]);
        if (targetPlayer != null) {
            if (isAdd) {
                minter.giveTo(targetPlayer, units);
                mintAndReport(sender, units, "to " + targetPlayer.getName());
            } else {
                long removed = minter.removeUnits(targetPlayer.getInventory(), units);
                destroyAndReport(sender, removed, units, "from " + targetPlayer.getName());
            }
            return;
        }

        if (args.length >= 5) {
            handleLocationTarget(sender, args, units, isAdd);
            return;
        }

        sender.sendMessage("Player '" + args[2] + "' is not online, and no x y z location was given.");
    }

    private void handleLocationTarget(CommandSender sender, String[] args, long units, boolean isAdd) {
        if (!(sender instanceof Player senderPlayer)) {
            sender.sendMessage("Targeting a location requires running this in-game (no world was given).");
            return;
        }
        Double x = parseCoord(sender, args[2]);
        Double y = parseCoord(sender, args[3]);
        Double z = parseCoord(sender, args[4]);
        if (x == null || y == null || z == null) {
            return;
        }
        Location location = new Location(senderPlayer.getWorld(), x, y, z);
        String locationDescription = "at " + location.getWorld().getName() + " " + x + " " + y + " " + z;

        if (isAdd) {
            minter.dropAt(location, units);
            mintAndReport(sender, units, locationDescription);
        } else {
            // "Removing" from a location has no single physical meaning; we treat it as the
            // literal inverse of /cheese add's drop — confiscate matching gold dropped as item
            // entities within a short radius. This will not find gold sitting in a nearby chest.
            long removed = removeFromNearbyItems(location, units);
            destroyAndReport(sender, removed, units, locationDescription);
        }
    }

    private long removeFromNearbyItems(Location location, long units) {
        long removed = 0;
        for (Entity entity : location.getWorld().getNearbyEntities(
                location, REMOVE_AT_LOCATION_RADIUS, REMOVE_AT_LOCATION_RADIUS, REMOVE_AT_LOCATION_RADIUS)) {
            if (removed >= units) {
                break;
            }
            if (!(entity instanceof Item itemEntity)) {
                continue;
            }
            ItemStack stack = itemEntity.getItemStack();
            long unitsPerItem = switch (stack.getType()) {
                case GOLD_INGOT -> CheeseUnits.INGOT_UNITS;
                case GOLD_NUGGET -> CheeseUnits.NUGGET_UNITS;
                default -> 0;
            };
            if (unitsPerItem == 0) {
                continue;
            }
            long itemsNeeded = (units - removed) / unitsPerItem;
            if (itemsNeeded <= 0) {
                continue;
            }
            long take = Math.min(stack.getAmount(), itemsNeeded);
            removed += take * unitsPerItem;
            int newAmount = (int) (stack.getAmount() - take);
            if (newAmount <= 0) {
                itemEntity.remove();
            } else {
                stack.setAmount(newAmount);
                itemEntity.setItemStack(stack);
            }
        }
        return removed;
    }

    private void mintAndReport(CommandSender sender, long units, String targetDescription) {
        try {
            storage.mint(units);
            sender.sendMessage("Minted " + units + " units of Cheese " + targetDescription
                    + " and raised the cap by the same amount.");
            plugin.getLogger().info(sender.getName() + " minted " + units + " units " + targetDescription + ".");
        } catch (SQLException e) {
            fail(sender, "record the mint", e);
        }
    }

    private void destroyAndReport(CommandSender sender, long removedUnits, long requestedUnits, String targetDescription) {
        if (removedUnits <= 0) {
            sender.sendMessage("No removable Cheese found " + targetDescription + ".");
            return;
        }
        try {
            storage.destroy(removedUnits);
            String note = removedUnits < requestedUnits
                    ? " (only " + removedUnits + " of the requested " + requestedUnits + " could be removed)"
                    : "";
            sender.sendMessage("Removed " + removedUnits + " units of Cheese " + targetDescription
                    + " and lowered the cap by the same amount." + note);
            plugin.getLogger().info(sender.getName() + " removed " + removedUnits + " units " + targetDescription + ".");
        } catch (SQLException e) {
            fail(sender, "record the removal", e);
        }
    }

    // ---- audit ----

    private void handleAudit(CommandSender sender) {
        if (!hasPermission(sender, "cheese.admin")) {
            deny(sender);
            return;
        }
        if (!checkAndUpdateCooldown(sender)) {
            return;
        }
        sender.sendMessage("Running a full Cheese audit — this may take a moment...");
        newScanner().scan(resolveWorlds(), result -> reportAudit(sender, result));
    }

    private void reportAudit(CommandSender sender, ScanResult result) {
        try {
            long tracked = storage.getCurrentSupply();
            long scanned = result.getTotal();
            long diff = scanned - tracked;
            sender.sendMessage(result.toReportString());
            if (diff == 0) {
                sender.sendMessage("Audit OK: scanned total matches tracked supply (" + tracked + " units).");
            } else {
                // Never auto-correct — see docs/SPEC.md "The scan command": silently adjusting
                // could punish innocent players for a mismatch caused by something else entirely.
                String message = "Audit MISMATCH: tracked=" + tracked + " scanned=" + scanned + " diff=" + diff
                        + " units. Not auto-corrected — investigate before adjusting.";
                sender.sendMessage(message);
                plugin.getLogger().warning(message);
            }
        } catch (SQLException e) {
            fail(sender, "read the tracked supply", e);
        }
    }

    // ---- shared helpers ----

    private FullEconomyScanner newScanner() {
        GoldCounter counter = new GoldCounter(config.getNestedContainerMaxDepth());
        return new FullEconomyScanner(plugin, counter, config.isIncludePlayerInventoriesInAudit());
    }

    private List<World> resolveWorlds() {
        List<World> worlds = new ArrayList<>();
        for (String name : config.getWorldNames()) {
            World world = Bukkit.getWorld(name);
            if (world != null) {
                worlds.add(world);
            }
        }
        return worlds;
    }

    private boolean checkAndUpdateCooldown(CommandSender sender) {
        long cooldownMillis = config.getFullScanCooldownSeconds() * 1000L;
        long now = System.currentTimeMillis();
        if (cooldownMillis > 0 && now - lastFullScanMillis < cooldownMillis) {
            long remainingSeconds = (cooldownMillis - (now - lastFullScanMillis)) / 1000;
            sender.sendMessage("A full scan ran recently — try again in " + remainingSeconds + "s.");
            return false;
        }
        lastFullScanMillis = now;
        return true;
    }

    private Long parseUnits(CommandSender sender, String raw) {
        try {
            long units = Long.parseLong(raw);
            if (units <= 0) {
                sender.sendMessage("Units must be a positive whole number.");
                return null;
            }
            return units;
        } catch (NumberFormatException e) {
            sender.sendMessage("'" + raw + "' isn't a valid whole number of units.");
            return null;
        }
    }

    private Double parseCoord(CommandSender sender, String raw) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            sender.sendMessage("'" + raw + "' isn't a valid coordinate.");
            return null;
        }
    }

    private void deny(CommandSender sender) {
        sender.sendMessage("You don't have permission to do that.");
    }

    /**
     * The server console always passes, regardless of the permission's registered default —
     * console/RCON access is a machine-level trust boundary, not an in-game one. Every other
     * sender (including OP'd players) goes through the normal permission check.
     */
    private boolean hasPermission(CommandSender sender, String permission) {
        return sender instanceof ConsoleCommandSender || sender.hasPermission(permission);
    }

    private void fail(CommandSender sender, String action, SQLException e) {
        sender.sendMessage("Failed to " + action + " — see the server log.");
        plugin.getLogger().log(Level.SEVERE, "Cheese command failed to " + action + ".", e);
    }

    // ---- tab completion ----

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return matching(args[0], SUBCOMMANDS);
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "scan" -> args.length == 2 ? matching(args[1], List.of("--loaded", "--full")) : List.of();
            case "cap" -> args.length == 2 ? matching(args[1], List.of("get", "set")) : List.of();
            case "add", "remove" -> args.length == 3 ? matching(args[2], onlinePlayerNames()) : List.of();
            default -> List.of();
        };
    }

    private List<String> matching(String prefix, List<String> options) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(option);
            }
        }
        return matches;
    }

    private List<String> onlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }
}
