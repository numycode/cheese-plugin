package dev.pyroforge.cheese.scan;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import dev.pyroforge.cheese.economy.GoldCounter;

/**
 * Async, batched "--full" sweep: discovers every chunk that exists on disk (loaded or not) via
 * {@link RegionFileChunkLister}, then loads/scans/unloads them a few at a time so the main
 * thread is never blocked. Chunk discovery and loading happen off/async; every inventory read
 * happens on the main thread, per docs/SPEC.md's threading boundary.
 *
 * <p>Block entities and world entities are both InventoryHolder-driven generically in this
 * Paper API version (Shelf, Decorated Pot, chested horses/llamas, chest boats, Allay, Piglin,
 * etc. all implement InventoryHolder), so a single polymorphic check covers nearly every scan
 * target in the spec without enumerating each concrete type.
 */
public final class FullEconomyScanner {

    private static final int CHUNKS_PER_TICK = 5;

    private final Plugin plugin;
    private final GoldCounter goldCounter;
    private final boolean includePlayerInventories;

    public FullEconomyScanner(Plugin plugin, GoldCounter goldCounter, boolean includePlayerInventories) {
        this.plugin = plugin;
        this.goldCounter = goldCounter;
        this.includePlayerInventories = includePlayerInventories;
    }

    /** Must be called from the main thread. {@code onComplete} also fires on the main thread. */
    public void scan(List<World> worlds, Consumer<ScanResult> onComplete) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<World, List<ChunkCoord>> perWorldChunks = new LinkedHashMap<>();
            for (World world : worlds) {
                try {
                    perWorldChunks.put(world, RegionFileChunkLister.list(new File(world.getWorldFolder(), "region")));
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to list region files for world " + world.getName(), e);
                    perWorldChunks.put(world, List.of());
                }
            }
            Bukkit.getScheduler().runTask(plugin, () -> runBatchedScan(perWorldChunks, onComplete));
        });
    }

    private void runBatchedScan(Map<World, List<ChunkCoord>> perWorldChunks, Consumer<ScanResult> onComplete) {
        ScanResult result = new ScanResult();
        Deque<WorldChunk> queue = new ArrayDeque<>();
        perWorldChunks.forEach((world, coords) -> coords.forEach(c -> queue.add(new WorldChunk(world, c))));

        AtomicInteger pending = new AtomicInteger(0);
        AtomicBoolean dispatchDone = new AtomicBoolean(false);
        BukkitTask[] taskHolder = new BukkitTask[1];

        taskHolder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (int i = 0; i < CHUNKS_PER_TICK && !queue.isEmpty(); i++) {
                WorldChunk wc = queue.poll();
                boolean alreadyLoaded = wc.world.isChunkLoaded(wc.coord.x(), wc.coord.z());
                pending.incrementAndGet();
                wc.world.getChunkAtAsync(wc.coord.x(), wc.coord.z(), false).thenAccept(chunk ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            try {
                                scanChunk(chunk, result);
                            } finally {
                                if (!alreadyLoaded) {
                                    wc.world.unloadChunk(chunk);
                                }
                                pending.decrementAndGet();
                            }
                        }));
            }
            if (queue.isEmpty()) {
                dispatchDone.set(true);
            }
            if (dispatchDone.get() && pending.get() == 0) {
                taskHolder[0].cancel();
                finishScan(result, onComplete);
            }
        }, 0L, 1L);
    }

    private void finishScan(ScanResult result, Consumer<ScanResult> onComplete) {
        if (includePlayerInventories) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                result.add("(players)", "player-inventory", goldCounter.countInventory(player.getInventory()));
                result.add("(players)", "ender-chest", goldCounter.countInventory(player.getEnderChest()));
            }
        }
        // Offline players are intentionally not scanned here: Paper exposes no public API for
        // an offline player's inventory/ender chest (see docs/SPEC.md). Their holdings are a
        // known, expected gap in this total, not a dupe.
        onComplete.accept(result);
    }

    private void scanChunk(Chunk chunk, ScanResult result) {
        String worldName = chunk.getWorld().getName();

        for (BlockState state : chunk.getTileEntities()) {
            if (state instanceof InventoryHolder holder) {
                result.add(worldName, "block-entities", goldCounter.countInventory(holder.getInventory()));
            }
        }

        // NOTE: Paper can load entities for a chunk on a separate schedule from block data
        // (chunk.isEntitiesLoaded()); validate against a real server that entities are present
        // by the time we read them here (see docs/SPEC.md manual test checklist).
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof HumanEntity) {
                // Players are scanned once, globally, in finishScan() — skip here to avoid
                // double-counting whichever chunk they happen to be standing in.
                continue;
            }
            if (entity instanceof InventoryHolder holder) {
                result.add(worldName, "entities", goldCounter.countInventory(holder.getInventory()));
            }
            if (entity instanceof ItemFrame frame) {
                result.add(worldName, "entities", goldCounter.countItemStack(frame.getItem()));
            }
            if (entity instanceof LivingEntity living) {
                EntityEquipment equipment = living.getEquipment();
                if (equipment != null) {
                    for (EquipmentSlot slot : EquipmentSlot.values()) {
                        result.add(worldName, "entities", goldCounter.countItemStack(equipment.getItem(slot)));
                    }
                }
            }
        }
    }

    private record WorldChunk(World world, ChunkCoord coord) {
    }
}
