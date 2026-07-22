# CLAUDE.md

Context for working on this repo. `docs/SPEC.md` is the original design spec (still the source
of truth for *intended* behavior); `README.md` is the user-facing doc. This file is for things a
future session needs to know that aren't obvious from either — decisions made, bugs found and
why, and how to actually verify changes on this project.

## What this is

A Paper **26.1.2** (Java 25) plugin. Gold Ingots/Nuggets **are** the currency ("Cheese"); the
plugin tracks how much exists and enforces a hard cap. 1 nugget = 1 unit, 1 ingot = 9 units,
tracked in SQLite. The server is **pure Creative mode** — everyone can otherwise pull unlimited
gold from the Creative menu — so the Creative-menu block and the three placement-dupe blocks are
the actual attack surface; survival hooks (smelting, mob drops, barter, crafting) are secondary
insurance that will rarely fire.

All 5 build-order steps from `docs/SPEC.md` are complete. There is no "step 6" — further work is
either polish, bug fixes found by testing, or scope the user explicitly asks for.

## Architecture (by package)

- `economy` — pure logic, no Bukkit event wiring. `GoldCounter` (counts gold in an ItemStack/
  Inventory, recursing into Bundles and filled Shulkers up to a configurable depth — only
  `GOLD_INGOT`/`GOLD_NUGGET` count, never golden armor/tools/blocks). `CheeseUnits` (unit math).
  `GoldMinter` (units ↔ physical ItemStacks for `/cheese add`/`remove`). `GoldSupplyGate`
  (cap-enforcement for creation events — all-or-nothing for indivisible single stacks,
  trim-to-fit with full nested-container recursion for batches). `CraftingGoldDiff` (matrix-vs-
  result diff for `CraftItemEvent`, with a Gold Block bypass).
- `creative` / `listener` — `CreativeGoldGuard` (pure logic deciding grab-vs-rearrange) +
  `CreativeInventoryListener` (thin event glue). Same split for `duplication` /
  `DuplicationGuardListener`.
- `survival` — `GoldCreationListener` (furnace smelt, Zombified Piglin drops, natural loot) and
  `GoldDestructionListener` (Piglin barter, crafting).
- `scan` — `FullEconomyScanner` (async batched `--full` sweep + synchronous `scanLoaded()`),
  `RegionFileChunkLister` (raw `.mca` header parsing), `ScanResult`.
- `storage` — `EconomyStorage`, single-writer SQLite behind one lock.
- `command` — `CheeseCommand`, the `/cheese` TabExecutor.
- `config` — `CheeseConfig`, thin wrapper that only exposes keys actually wired to logic.

## Deliberate decisions (don't "fix" these back to spec-literal or add a check that undoes them)

- **Console bypasses `cheese.admin`/`cheese.use` entirely**; OP status never does. This was a
  user correction, not my initial assumption — see `CheeseCommand.hasPermission(CommandSender,
  String)`. Console/RCON access is already full machine control; OP is an in-game status any
  family member gets and must never silently expand to economy-admin rights.
- **`EconomyStorage.setMaxSupply` rejects negative caps but allows a cap below
  `currentSupply`.** An admin tightening the cap without an immediate clawback (freezing new
  minting until natural destruction brings supply back under it) is legitimate and distinct
  from `/cheese remove`, which lowers both together. CodeRabbit flagged this twice; both times
  the finding was withdrawn after this reasoning. Don't add the "reject below currentSupply"
  check — see the method's javadoc and `EconomyStorageTest.setMaxSupplyAllowsSettingBelowCurrentSupply`.
- **Gold Blocks are parked outside the tracked economy on purpose.** Never counted by
  `GoldCounter`, never scanned, and `CraftingGoldDiff` bypasses the cap check entirely in both
  directions (ingots→block and block→ingots) whenever a craft involves `Material.GOLD_BLOCK`.
  This is not a scan gap; it's the documented design (see SPEC.md "Gold Blocks").
- **`/cheese remove <units> x y z`** (no matching player) confiscates gold from *dropped item
  entities* within a 3-block radius — chosen as the literal physical inverse of `add`'s drop,
  since the spec doesn't define what "remove from a location" means. Does **not** reach into a
  nearby chest. This was a judgment call, not a spec quote — flag it if it ever needs revisiting.
- **Offline-player inventories/ender chests are not scanned** (`includePlayerInventoriesInAudit`
  only covers online players). Bukkit/Paper exposes no public API for this; treated as a known,
  expected audit gap, not a dupe.
- **The `CraftItemEvent` shift-click multiplier is a known, unfixed gap** — see
  `CraftingGoldDiff`'s class doc. A shift-clicked bulk craft may fire the event once for many
  actual crafts, under-counting gold consumed. Deliberately not "fixed" with a guessed
  multiplier: verifying real event-firing semantics for bulk crafts needs a connected Minecraft
  client, unavailable in this environment, and an unverified multiplier risks being wrong in a
  harder-to-detect way than the documented gap. `/cheese audit` is the backstop.

## Bugs found and fixed (context for why the current code looks the way it does)

All of these were found via **actually running a real Paper 26.1.2 server locally**, not by
inspection — see "Live-server testing" below. Don't assume similar-looking code elsewhere is
correct just because it compiles and passes MockBukkit tests.

1. **`FullEconomyScanner` NPE on `null` chunks.** `world.getChunkAtAsync(x, z, gen=false)`
   resolves to `null` (not an exception) when a chunk can't be loaded that way. Fixed with a
   null check + bounded retry (5 attempts) as a safety net for genuine transient timing.
2. **Seed scan silently seeded `currentSupply=0` on a fresh world.** The scan raced ahead of
   the world's own disk flush — freshly-generated spawn chunks existed only in memory when
   `RegionFileChunkLister` read the (empty) region folder. Fixed with a forced synchronous
   `world.save()` before listing region files, in `FullEconomyScanner.scan()`.
3. **`--full` scans near-hung for 5+ minutes.** A region file's location-table entry means
   "generation touched this chunk at all," not "fully generated" — neighbor-chunk generation
   passes write partial data for a much wider halo than the playable area. Of ~1587 chunks
   around one fresh spawn, most never reached FULL status, and `gen=false` resolves `null` for
   those **forever**, not transiently — so blind retrying wasted huge amounts of time. Fixed by
   pre-filtering with `World.isChunkGenerated(x, z)` (confirmed to exist via `javap` against the
   real jar) and skipping — not retrying — anything that fails it.
4. **Double chests were counted twice.** `Chest#getInventory()` returns the *merged* 54-slot
   inventory from **either** physical half. `scanChunk` visits both halves' `BlockState`
   separately, so it read that merged inventory twice per double chest. Confirmed on a real
   server: 1 ingot in a double chest reported as 18 units instead of 9. Fixed by special-casing
   `instanceof Chest` (covers Trapped Chest, which shares the interface; also correct for Copper
   Chest, which has no separate class and doesn't merge) to use `Chest#getBlockInventory()` —
   the block's own unmerged 27 slots, which Bukkit exposes specifically for this.
5. **`GoldSupplyGate.admitList` let currentSupply drift upward with no matching item** (found by
   a test, not inspection). When a batch got trimmed to fit and the trim rounded *down* to whole
   ingots, the originally-granted unit count wasn't corrected back down. Fixed by having the
   trim step return actual-units-kept and reconciling the gap via `decreaseCurrentSupply`.
6. **Nested-container trimming destroyed unrelated non-gold items.** Early version of
   `GoldSupplyGate` removed a Bundle/Shulker *entirely* whenever its gold couldn't be admitted,
   destroying anything else inside (e.g. a STICK). Fixed: a container is only removed once it's
   completely empty after trimming; non-gold contents always survive. A second bug surfaced
   while fixing this — `trimStack`'s early `allowed <= 0` return skipped recursing into
   containers at all, so rejected gold never actually got stripped out. Both fixed together.

## API facts verified against the real paper-api jar (don't re-derive, don't trust docs alone)

- Zombified Piglin's Bukkit class is `org.bukkit.entity.PigZombie` — its legacy pre-1.16 name.
  `ZombifiedPiglin` does not exist.
- `Chest#getBlockInventory()` exists specifically to read a single physical block's unmerged
  contents, as distinct from `getInventory()` which merges double chests.
- `World#isChunkGenerated(int, int)` is the authoritative "is this chunk actually usable" check
  — not the region-file header's mere presence.
- Minecraft 26.1's on-disk world layout changed: no more `<world>/region`,
  `<world>/DIM-1/region`. Everything now lives under
  `<world>/dimensions/minecraft/<overworld|the_nether|the_end>/region/`, nested inside the
  *single* primary world folder. Turned out to be a non-issue for us: `World.getWorldFolder()`
  already abstracts this correctly per-dimension in the public API.
- InventoryHolder coverage is broad in this API: Shelf and Decorated Pot via
  `TileStateInventoryHolder`→`BlockInventoryHolder`, chested horses/llamas via `AbstractHorse`,
  chest boats, Allay, Piglin's hidden slots, Villager/WanderingTrader/Pillager all implement it
  directly or via a superinterface — a single `instanceof InventoryHolder` check covers most of
  the spec's scan-target list. `ArmorStand` is the one exception (armor/off-hand only reachable
  via `LivingEntity#getEquipment()`, which the scanner already has as a separate branch).
  `EnderChest` (block) does **not** implement InventoryHolder — by design, ender storage is
  per-player, correctly only reached via `player.getEnderChest()`.
- `Copper Chest` has no separate BlockState class — reuses `org.bukkit.block.Chest`,
  differentiated only by Material.
- `EquipmentSlot.BODY`/`SADDLE` exist in this API (horse armor/saddle), but this is a moot
  double-counting concern: `GoldCounter` only ever counts `GOLD_INGOT`/`GOLD_NUGGET` materials,
  so golden equipment is never counted via any path regardless.
- `mockbukkit-v26.1.2:4.114.0` is a real Maven Central artifact (verify surprising-looking
  coordinates against live metadata before assuming they don't exist).

## Testing approach

- **Pure logic** (`economy`, `creative`, `duplication` packages) — thorough MockBukkit/JUnit unit
  tests. This is most of the 93 tests.
- **Thin event-glue listeners** — tested by constructing real Bukkit event objects directly where
  the constructor is simple enough (`FurnaceSmeltEvent`, `LootGenerateEvent`,
  `PiglinBarterEvent`), or by dispatching through the real event bus
  (`server.getPluginManager().callEvent(...)`) when what's under test is the `@EventHandler`
  annotation itself (e.g. `ignoreCancelled = true`, or `PlayerQuitEvent` cleanup wiring) — a
  direct method call doesn't exercise annotation-based dispatch semantics at all.
- **Not unit-testable, live-server only** (documented as such in the code, not silently
  skipped): the async chunk-loading path in `FullEconomyScanner`, and the double-chest merge
  behavior (MockBukkit has no block-placement or double-chest simulation). `EntityDeathEvent`
  and `CraftItemEvent` also aren't constructed directly in tests — `DamageSource`/`InventoryView`
  aren't worth the risk of testing something that doesn't match a live server; their pure logic
  (`GoldSupplyGate`, `CraftingGoldDiff`) has full coverage instead.
- When constructing a Bukkit event directly for a test, don't hand it something *unrealistic*
  (e.g. an immutable list) just to probe a defensive-copy fix — check whether the event's own
  methods (like `setLoot()`) assume mutability of what the constructor received; an unrealistic
  construction can produce a failure that has nothing to do with the code under test.

## Live-server testing (use this when something needs real-server verification)

This project's most valuable bug-finding tool has been running the actual Paper 26.1.2 server
locally rather than reasoning from docs or memory. Pattern used throughout this session:

```bash
# One-time: download the real server jar matching the version this project compiles against
curl -sL -o paper.jar "https://fill-data.papermc.io/v1/objects/<sha256>/paper-26.1.2-74.jar"
# (get the URL from: curl -s https://fill.papermc.io/v3/projects/paper/versions/26.1.2/builds)
# api.papermc.io v2 is sunset; the v3 web UI is Cloudflare-blocked for curl — use the JSON
# build-list endpoint directly.

echo "eula=true" > eula.txt
cp build/libs/cheese-plugin-0.1.0.jar plugins/

mkfifo server_in
(java -Xmx2G -jar paper.jar --nogui < server_in > server.log 2>&1) &
exec 3>server_in
# poll server.log for "Done (" before sending commands
echo "some console command" >&3
# poll server.log for the expected output marker
echo "stop" >&3
wait
```

Notes from using this:
- Console commands (like `/cheese scan --full`) now work directly since console bypasses
  permission checks — no need to grant `cheese.admin` for testing.
- `/data get block x y z` is useful as ground truth independent of the plugin, when verifying a
  scan/count result (e.g. confirming a chest's actual NBT contents before checking what the
  scanner reports).
- `/setblock`/`/forceload` need `forceload add` first if no player has ever loaded that area —
  a truly fresh world has nothing chunk-loaded by default.
- Reusing the same world folder across many test runs accumulates leftover state from earlier
  experiments; `rm -rf world plugins/CheesePlugin` for a genuinely clean run when the test
  depends on starting from zero (e.g. verifying seed-scan behavior).
- Watch for a stale `java` process still bound to port 25565 from a previous run
  (`lsof -ti :25565 | xargs -r kill -9`) — a second server instance fails to bind and none of
  your queued console commands will do anything.

## Working with CodeRabbit reviews on this PR

- Findings frequently re-appear across review rounds even after being fixed — **always verify
  against current code before acting**, don't assume a finding is new just because it was just
  posted.
- Reply to review comment threads via REST:
  `gh api repos/OWNER/REPO/pulls/PR/comments/COMMENT_ID/replies -f body="..."`.
- REST has no "resolve thread" endpoint — that's GraphQL only:
  query `reviewThreads` for the thread's node ID, then `resolveReviewThread(input: {threadId: ...})`.
- CodeRabbit will often auto-resolve a thread itself once it sees the fix in a later commit, or
  reply acknowledging/withdrawing a finding after reading an explanation — check thread
  resolution state before assuming you need to manually resolve everything.
- When declining a suggested fix (a deliberate design decision, not a bug), reply with the
  reasoning rather than silently ignoring it — CodeRabbit has accepted and withdrawn findings
  after a clear explanation more than once in this project.

## Build

```bash
./gradlew build
```

Produces one artifact: `build/libs/cheese-plugin-0.1.0.jar` (shaded, sqlite-jdbc bundled and
relocated). The plain `jar` task is deliberately disabled (`tasks.jar { enabled = false }`) —
`shadowJar`'s classifier-less output would otherwise collide with it at the same path, and the
unshaded jar is non-functional anyway (missing sqlite-jdbc). See `build.gradle.kts` comments.

## CI and releases (`.github/workflows/`)

- `build.yml` — runs `./gradlew build` (compile + test + shadowJar) on every push to any branch,
  and uploads the resulting jar as a workflow artifact. Pure sanity check; no publishing.
- `release.yml` — fires on pushing a tag matching `v*.*.*`. It first checks that the tag (minus
  the `v`) matches the `version` in `build.gradle.kts` via `./gradlew properties --property
  version`, and fails loudly if they don't match — this catches the easy mistake of tagging
  without bumping the version first (the jar filename is derived from `project.version`, not the
  git tag, so a mismatch would silently publish a wrongly-named or stale-content jar). It then
  builds and runs `gh release create` (the GitHub CLI, preinstalled on runners — deliberately not
  a third-party marketplace action, to keep the release step's trust surface minimal) to publish
  the shaded jar as a release asset with auto-generated release notes.
- **To cut a release:** bump `version` in `build.gradle.kts`, commit, then `git tag vX.Y.Z && git
  push origin vX.Y.Z`. Don't tag before bumping — the version-match check will reject it.
- Both workflows use Temurin 25 (`actions/setup-java@v4`, `distribution: temurin`), matching the
  toolchain in `build.gradle.kts`.
