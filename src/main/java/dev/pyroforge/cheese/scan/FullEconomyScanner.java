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
import org.bukkit.block.Chest;
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
 * Paper API version — verified against the real paper-api jar, not assumed: Shelf and Decorated
 * Pot implement it via {@code TileStateInventoryHolder}, chested horses/llamas via
 * {@code AbstractHorse}, chest boats/Allay/Piglin/Pillager/villagers directly — so a single
 * polymorphic check covers nearly every scan target in the spec without enumerating each concrete
 * type. {@link Chest} (including Trapped Chest, which shares the same interface) is the one
 * deliberate exception — see the comment in {@link #scanChunk} for why it needs its own branch.
 */
public final class FullEconomyScanner {

    private static final int CHUNKS_PER_TICK = 5;
    // A region file's location table marks a chunk as "present" the moment world generation
    // touches it at all, including chunks generation only partially processed as a side effect of
    // finishing a NEIGHBORING chunk (structure/noise/biome passes need data from nearby chunks).
    // Reproduced firsthand: of ~1587 chunks RegionFileChunkLister found around a freshly generated
    // spawn area, the vast majority were this kind of "on disk but never reached FULL status"
    // halo — world.getChunkAtAsync(x, z, gen=false) resolves to null for these FOREVER, not
    // transiently, since gen=false explicitly refuses to advance a chunk's generation. Blindly
    // retrying those is pure waste (burns through this many retries × ~1500 chunks before the
    // scan finishes). world.isChunkGenerated(x, z) is the actual authoritative signal — skip
    // (not retry) anything that fails it. What's left after that filter is retried a few times
    // only as a safety net for a genuine transient race (e.g. right after server startup, before
    // scan()'s forced world.save() below has necessarily been observed by the chunk system yet).
    private static final int MAX_CHUNK_LOAD_RETRIES = 5;

    private final Plugin plugin;
    private final GoldCounter goldCounter;
    private final boolean includePlayerInventories;

    public FullEconomyScanner(Plugin plugin, GoldCounter goldCounter, boolean includePlayerInventories) {
        this.plugin = plugin;
        this.goldCounter = goldCounter;
        this.includePlayerInventories = includePlayerInventories;
    }

    /**
     * Synchronous scan of only the chunks already resident in memory right now — no async chunk
     * loading, so it's fast and safe to run anytime (backs {@code /cheese scan --loaded}). Must
     * be called from the main thread.
     */
    public ScanResult scanLoaded(List<World> worlds) {
        ScanResult result = new ScanResult();
        for (World world : worlds) {
            for (Chunk chunk : world.getLoadedChunks()) {
                scanChunk(chunk, result);
            }
        }
        addPlayerInventories(result);
        return result;
    }

    /** Must be called from the main thread. {@code onComplete} also fires on the main thread. */
    public void scan(List<World> worlds, Consumer<ScanResult> onComplete) {
        // Force a synchronous flush before reading raw region files off-thread. Reproduced
        // firsthand: on a brand-new world, calling this right after "Prepared spawn area" (i.e.
        // exactly when the plugin's first-run seed scan fires) found ZERO chunks on disk — the
        // freshly-generated spawn chunks existed only in memory, hadn't been saved yet, and
        // RegionFileChunkLister silently read an empty/near-empty region folder. That would seed
        // currentSupply=0 with no warning at all on a world that may already hold plenty of gold
        // — exactly what docs/SPEC.md calls out as "not optional" to get right.
        for (World world : worlds) {
            world.save();
        }
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
        perWorldChunks.forEach((world, coords) -> coords.forEach(c -> queue.add(new WorldChunk(world, c, 0))));

        AtomicInteger pending = new AtomicInteger(0);
        AtomicBoolean dispatchDone = new AtomicBoolean(false);
        BukkitTask[] taskHolder = new BukkitTask[1];

        taskHolder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (int i = 0; i < CHUNKS_PER_TICK && !queue.isEmpty(); i++) {
                WorldChunk wc = queue.poll();
                // Present in the region file's location table but never actually reached FULL
                // generation status (see MAX_CHUNK_LOAD_RETRIES's doc) — gen=false will never
                // resolve these no matter how many times we ask, so don't even try. This isn't an
                // error; it's the normal, expected majority of what a region file lists.
                if (!wc.world.isChunkGenerated(wc.coord.x(), wc.coord.z())) {
                    continue;
                }
                boolean alreadyLoaded = wc.world.isChunkLoaded(wc.coord.x(), wc.coord.z());
                pending.incrementAndGet();
                wc.world.getChunkAtAsync(wc.coord.x(), wc.coord.z(), false).thenAccept(chunk ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            try {
                                if (chunk == null) {
                                    retryOrGiveUp(wc, queue);
                                    return;
                                }
                                scanChunk(chunk, result);
                            } finally {
                                if (chunk != null && !alreadyLoaded) {
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

    private void retryOrGiveUp(WorldChunk wc, Deque<WorldChunk> queue) {
        if (wc.attempt < MAX_CHUNK_LOAD_RETRIES) {
            queue.add(new WorldChunk(wc.world, wc.coord, wc.attempt + 1));
            return;
        }
        plugin.getLogger().warning("Full scan: chunk (" + wc.coord.x() + ", " + wc.coord.z()
                + ") in world " + wc.world.getName() + " is generated but could not be loaded after "
                + MAX_CHUNK_LOAD_RETRIES + " attempts — skipping it.");
    }

    private void finishScan(ScanResult result, Consumer<ScanResult> onComplete) {
        addPlayerInventories(result);
        onComplete.accept(result);
    }

    private void addPlayerInventories(ScanResult result) {
        if (includePlayerInventories) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                result.add("(players)", "player-inventory", goldCounter.countInventory(player.getInventory()));
                result.add("(players)", "ender-chest", goldCounter.countInventory(player.getEnderChest()));
            }
        }
        // Offline players are intentionally not scanned here: Paper exposes no public API for
        // an offline player's inventory/ender chest (see docs/SPEC.md). Their holdings are a
        // known, expected gap in this total, not a dupe.
    }

    private void scanChunk(Chunk chunk, ScanResult result) {
        String worldName = chunk.getWorld().getName();

        for (BlockState state : chunk.getTileEntities()) {
            if (state instanceof Chest chest) {
                // Chest#getInventory() (the generic InventoryHolder path below) returns the
                // MERGED 54-slot DoubleChestInventory for either physical half of a double
                // chest — reading it from both halves' BlockState (as this loop naturally does)
                // would double-count everything inside. getBlockInventory() is this block's own
                // unmerged 27 slots, so summing it across both halves gives the true total.
                // Confirmed firsthand against a real server: a double chest holding a single
                // gold ingot was reported as 18 units (2x 9) via getInventory(), 9 via this.
                // Also correct for Trapped Chest (shares this same interface) and Copper Chest
                // (doesn't merge at all, so getBlockInventory() == getInventory() for it anyway).
                result.add(worldName, "block-entities", goldCounter.countInventory(chest.getBlockInventory()));
            } else if (state instanceof InventoryHolder holder) {
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

    private record WorldChunk(World world, ChunkCoord coord, int attempt) {
    }
}
