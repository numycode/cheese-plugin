# Cheese Economy Plugin — Paper 26.1.2

Build a Paper 26.1.2 (Java) plugin that manages a physical currency called **Cheese**, which is literally vanilla **Gold Ingots** and **Gold Nuggets** (a client-side resource pack reskins them — the plugin does not need to touch textures or resource packs at all).

> **Version note.** 26.1.2 is a real target: Minecraft moved to year-based versioning in 2026, where `26` is the year, `.1` is the first game drop ("The Tiny Takeover"), and `.2` is a patch. Requires **Java 25** (26.1 is the first Minecraft version to require it — the bundled runtime is Microsoft OpenJDK 25, and a server started on Java 21 or older will fail with `UnsupportedClassVersionError`). One correction that affects nothing but is worth having right: **Copper Chest, Shelf, and the Copper Golem were NOT added in 26.1.2 — they shipped in The Copper Age, Java 1.21.9, in autumn 2025.** They still exist in 26.1.2, so they remain valid scan targets, but their API is ~half a year mature rather than brand-new. Lean on 1.21.9-era API docs for them; the "very new, might not have API parity" worry below is overstated.

## Core concept

Cheese is not a new item. It IS Gold Ingots/Nuggets. The plugin's job is to:
1. Track how much Gold Ingot/Nugget "value" currently exists in the world/economy.
2. Enforce a hard supply cap on that value.
3. Let admins raise the cap (and mint new gold into existence) or lower it (and destroy gold out of existence).

Use a single unit for all math to avoid ingot/nugget rounding issues:
- **1 nugget = 1 unit**
- **1 ingot = 9 units**
- Track `currentSupply` and `maxSupply` in units, both persisted (SQLite recommended over YAML — this data needs to survive crashes and support atomic increments).

**Seed `currentSupply` on first run — this is not optional.** Event-driven tracking only knows about gold that enters/leaves *after* the plugin is running. On install (or on any world that already has gold), `currentSupply` starts at 0 while the world is full of gold, so the tracker and the audit will disagree forever and every audit will look like a massive "missing gold" event. On first startup (detect via a flag row in SQLite), run one full scan and set `currentSupply` to the total it finds. Only after that does event tracking mean anything.

## Server context: pure Creative mode

Every player is in Creative mode, all the time. This changes which vectors actually matter:

- **The Creative inventory is the real threat, not survival gameplay.** Any player can open it, search "gold," and take unlimited Gold Ingots/Nuggets instantly. This has to be blocked directly — see below — or the whole cap is meaningless regardless of how well the survival-side event hooks work.
- **You can't remove an item from the client's Creative menu itself.** That menu is rendered client-side from the vanilla item registry; there's no Paper API to strip an entry out of it, and a resource pack can only change how the item looks, not whether it's listed. The practical equivalent is intercepting the *grab*: cancel `InventoryCreativeEvent` when the item involved is `GOLD_INGOT` or `GOLD_NUGGET`. The item stays visible in the menu, but clicking it does nothing — functionally the same result as if it were removed, for a lot less engineering effort. Pair it with a short action-bar message ("Gold Ingots/Nuggets are managed by /cheese — ask an admin"), **rate-limited** (e.g. one message per player per second) so spamming the blocked slot doesn't flood the player.
  - **Careful: `InventoryCreativeEvent` is broader than "grab from the menu."** In Creative mode the client manages its own inventory locally and reports state to the server via creative set-slot packets, so this event ALSO fires when a player merely rearranges their own inventory. A blanket "cancel whenever gold is involved" will block a player from moving their *own legitimately-granted* gold around — or silently eat it. The handler has to distinguish a fresh stack materializing from the creative item panel from an existing stack being moved. `InventoryCreativeEvent` doesn't hand you that cleanly; you'll need to inspect the slot and the item already there (moving gold that was already in the slot vs. a new stack appearing in an empty/different slot) and test it against real in-inventory drags. Do not ship the naive one-line version.
  - **Verify the pick-block (middle-click) path.** In Creative, middle-clicking a Gold Block, or a gold item displayed in a frame, copies it straight into the player's hand. Confirm on your build whether this routes through `InventoryCreativeEvent` — if it doesn't, it's a wide-open bypass of this entire block and needs its own handler.
- **Creative mode's built-in "no item consumption" behavior is a duplication path for this specific item.** In Creative mode, placing an item into an Item Frame, on an Armor Stand, or into a Decorated Pot doesn't remove it from your hand — it duplicates it. That's normal and intentional for every other item in the game, but it means a single legitimately-granted gold ingot could be turned into unlimited gold. Since Gold Ingots/Nuggets need to stay scarce, block this specifically for gold:
  - Cancel `PlayerInteractEntityEvent` when the clicked entity is an (empty) Item Frame or Glow Item Frame and the player's held item is a Gold Ingot/Nugget.
  - Cancel `PlayerArmorStandManipulateEvent` when the item being placed is a Gold Ingot/Nugget.
  - Cancel `PlayerInteractEvent` when the clicked block is a Decorated Pot and the held item is a Gold Ingot/Nugget.
  - Trade-off worth knowing: this also means nobody can decoratively display a Gold Ingot/Nugget in a frame, on a stand, or in a pot anymore — the same mechanism that enables the dupe is the only mechanism for decorative placement. Given it's a small server, that's a reasonable trade for keeping the currency meaningful.

Given the survival-gameplay vectors below (smelting, mob drops, natural loot chests) will rarely if ever fire on a pure Creative server, they're worth keeping as cheap insurance (in case someone ever switches to Survival for a build challenge, or a stray structure gets naturally interacted with) but shouldn't be where the implementation effort goes first — the Creative-menu block and the frame/stand/pot block above are what actually matter here.

**Build order for THIS server (Creative-only), most-to-least important — follow this rather than "event-driven first":**
1. Seed `currentSupply` via an initial full scan (above). Nothing works until this exists.
2. Creative-menu grab block (`InventoryCreativeEvent`, done properly per the caveats above) + pick-block check.
3. Dupe blocks: item frame / armor stand / decorated pot.
4. Admin commands (`/cheese add`, `remove`, `cap`) and the audit/scan.
5. Survival creation/destruction hooks (smelting, mob drops, loot chests, barter, etc.) as cheap insurance that will almost never fire here.

The "build event-driven tracking first, it's the primary enforcement" framing further down is written for a Survival server. On an all-Creative server the survival hooks are near-dead code, so don't sink the first day of effort into them before the Creative block is solid.

## Why a scan alone can't enforce the cap

A scan command only tells you the total at one moment. Gold enters the server continuously through normal survival gameplay:
- Smelting raw gold / gold ore into ingots (furnace, blast furnace)
- Zombified Piglins dropping Gold Nuggets (near-guaranteed) and occasionally a Gold Ingot (roughly 2.5–5.5% depending on Looting) from their death loot table — this is the primary gold-farming method in vanilla survival, so it's a high-volume vector, not an edge case
- Natural loot chests generating gold ingots the first time they're opened (mineshafts, bastions, nether fortresses, shipwrecks, buried treasure, etc.) — this fires via `LootGenerateEvent`
- Crafting 9 nuggets → 1 ingot or 1 ingot → 9 nuggets — **net-zero in units (9 → 9), so there is nothing to track. Do not hook this; the earlier "worth listening for to keep counts sane" was wrong since you track units, not item counts.**

And gold leaves the server through:
- Burning in lava/fire (dropped items destroyed)
- Piglin bartering (players give ingots away for other loot — net removal from circulation) — Paper exposes `PiglinBarterEvent`, so this one is cleanly hookable
- Crafting into derived items — once converted, that gold is no longer "Cheese" and should be decremented. **Do not enumerate the recipes** (the list is long and easy to under-count: golden apple, golden carrot, powered rail, clock, light weighted pressure plate, glistering melon, horse armor, golden armor/tools, enchanted golden apple, etc.). Instead, on `CraftItemEvent` **diff the crafting matrix**: count the gold ingot/nugget units in the inputs that don't appear in the output and decrement that. This is robust and covers recipes you didn't think of.
- Falling into the void, `/kill`, etc.
- **Beacon activation** — confirming a power with a gold ingot payment consumes it permanently
- **Anvil repair with material** — repairing a golden tool/armor piece with gold ingots consumes them
- **Grindstone** — doesn't consume raw ingots, but destroys one of two golden tools/armor pieces when combining them (worth tracking if you're accounting for gold locked in golden equipment, not just raw ingots/nuggets)

**Reality check on those last three:** unlike smelting/loot/barter, there is **no dedicated Bukkit event** for a beacon payment, an anvil material consumption, or a grindstone combine. Detecting them means hooking the respective inventory GUIs (`InventoryClickEvent` on the result/payment slot) and inferring consumption, which is fiddly and error-prone. On a pure Creative server they essentially never fire (beacons need a pyramid, gold is scarce by design). **Recommendation: don't build custom hooks for beacon/anvil/grindstone. Let the periodic audit account for any gold that leaves this way.** They're listed here for completeness, not as a to-do.

**Gold Blocks — pick a rule and state it, or the math breaks.** You track ingots/nuggets in units, but a Block of Gold = 9 ingots = 81 units, and crafting is *reversible* (ingots ⇄ block). The scan can't cheaply find placed gold blocks (that would mean iterating every block in every chunk, which you're deliberately avoiding). So if a player crafts 9 ingots into a block, the scan stops finding those 81 units → every audit reports a phantom "81 missing" that looks exactly like a dupe. But if you "fix" that by decrementing on the ingots→block craft, then crafting the block *back* into ingots re-adds 81 units, which can trip the cap and cancel the player's own legitimate craft. There's no clean answer without either scanning all blocks (too expensive) or a deliberate rule. **Use this rule: treat gold in block form as "parked outside the tracked economy." Do NOT count gold blocks in the scan, do NOT decrement when ingots→block, and make block→ingots crafting ALWAYS bypass the cap** (it's re-entry of the player's own gold, not minting). Implement the bypass in the same `CraftItemEvent` handler that does the matrix diff: if the output is 9 ingots from 1 gold block, skip the cap check. State this behavior in a comment so it isn't "fixed" later into a bug.

**Therefore: build event-driven tracking as the primary cap enforcement**, not the scan. Hook the events above; when a creation event would push `currentSupply` over `maxSupply`, cancel it (or reduce the yield) and notify the player the Cheese economy is at capacity. Use the scan command as a periodic **audit** to catch drift the event hooks missed (dupe glitches, other plugins, console-spawned items, admin `/give` commands) — flag discrepancies to an admin log rather than silently correcting them, since silently deleting mismatched gold could punish innocent players.

This event-driven design is the main architectural addition beyond the original scan-only idea — implement it first, since it's what actually makes the cap real.

## The scan command

Command: `/cheese scan [--loaded | --full]`

- `--loaded` (default): scans only currently loaded chunks across configured worlds. Fast, safe to run anytime, no special permission needed beyond basic use.
- `--full`: async, batched sweep that also covers unloaded chunks (load a handful of chunks per tick via Paper's async chunk API, then unload them again — do not block the main thread). Admin-only permission, rate-limited (e.g., one `--full` scan per configurable cooldown, default 1 hour) since even batched loading touches a lot of disk I/O on a large world.
  - **Threading boundary — get this right or you'll get async-access crashes.** Chunk *loading* can be async, but *reading* a block entity's or an entity's inventory is **not thread-safe** and must happen on the main thread (or a Folia region thread). The safe pattern: load chunks async, then hop back to the main thread (`Bukkit.getScheduler().runTask(...)` or a region scheduler) to read the inventories, batching a few chunks per tick so you never freeze the server. Do not call `getInventory()` / read `BlockState` contents off-thread.

Scan targets — split into two passes, since they're accessed differently in the API:

**Block entities (tile entities):**
Chest, Trapped Chest, Barrel, Copper Chest (confirm the exact Paper API class name for your build — note copper chests do **not** merge into a double chest, so scan each one as an independent 27-slot container), Hopper, Shelf (holds up to 3 stacks of *any* item including gold — a real target; confirm the Paper API class), Crafter, Dropper, Dispenser, Decorated Pot (single item slot, no GUI — read its container data directly, don't try to open an inventory view), Furnace, Blast Furnace, Smoker, Shulker Box (placed block form).

Chiseled Bookshelf holds books only — it can never contain gold, so put it in the "always reports zero, don't special-case" bucket alongside Lectern and Jukebox below, rather than treating it as an active target.

**Entities (not blocks — need a separate `world.getEntities()` pass):**
Minecart with Chest, Minecart with Hopper, Minecart with Furnace, Item Frame and Glow Item Frame, chested Llama/Donkey/Mule, Armor Stand (armor slots + off-hand), Allay (carries a single item in a dedicated inventory slot, separate from the item in its hand that it's told to collect).

**Piglin** — has 8 hidden inventory slots (not player-visible, but readable via NBT/API) that permanently hold Gold Nuggets and other `piglin_loved` items it's picked up off the ground. That's real, persistent gold sitting on the server and belongs in the scan. Separately, a piglin has a ~6-second "examining" window where a Gold Ingot given to it sits in its mainhand before the barter consumes it (see destruction events below) — don't double-count an ingot that's mid-barter as both "held by the piglin" and "about to be destroyed."

**Zombified Piglin / Piglin Brute** — spawn already equipped with a golden sword, spear, axe, or crossbow depending on origin. That's equipment (tool/weapon form), not raw Ingots/Nuggets, so it's out of scope for the Cheese count — same rule as any other golden gear. Don't scan their equipment slots for this reason; their relevance is entirely on the death-loot side, covered above.

**Explicitly excluded — Enderman:** Endermen carry certain blocks around, which sounds relevant at first glance, but Gold Block is not on the vanilla `enderman_holdable` block list — there's no code path where an Enderman ends up holding trackable gold. Skip it.

Chest boats/rafts, enumerated explicitly rather than "all wood types" (confirmed against the Paper API's `InventoryHolder` list): Oak, Spruce, Birch, Jungle, Acacia, Dark Oak, Mangrove, Cherry, Pale Oak Chest Boat, plus Bamboo Chest Raft.

**Equipment slots (separate inventory from the chest, on the same animal):** Horse, Donkey, Mule, Llama, Trader Llama, Skeleton Horse, Zombie Horse, Camel — each has its own saddle/armor equipment slot, which can hold Golden Horse Armor specifically. Check this in addition to (not instead of) the chest inventory on chested variants.

Also technically implement `InventoryHolder` in the Paper API and are cheap to include even though they're unlikely to naturally hold gold: Villager, Wandering Trader, Pillager. Worth a quick check by whoever's building this as to whether they're worth the extra entity iteration, or safe to skip.

**Special case — Ender Chests:** the placed block stores nothing. Contents belong to the player, stored in their personal ender inventory (works the same for online and offline players via their player data). Scan every known player's ender inventory once total, not once per physical ender chest block in the world — otherwise you'd count the same player's gold repeatedly for every ender chest they've ever placed.

**Rest of the Paper API `InventoryHolder` list, accounted for explicitly** (so nothing from the API is silently missing from this spec):

- **Brewing Stand** — has real inventory slots (3 potions, 1 ingredient, 1 fuel), but gold ingots/nuggets are never a valid ingredient or fuel there under any recipe. Fine to include in a generic container-scanner for architectural consistency — it'll just always report zero — but not worth special-casing.
- **Jukebox** — one slot, music discs only. Never gold. Skip.
- **Lectern** — one slot, books only. Never gold. Skip.
- **Player (`HumanEntity`)** — this is the one that actually matters, and it's easy to miss. A player's carried inventory (main inventory, hotbar, off-hand, armor slots) can hold raw ingots/nuggets or golden armor, and none of that lives in any block or container. For the `--full` audit to actually reconcile against `currentSupply`, it has to read every player's carried inventory too. Skip this and the audit will show a permanent phantom "missing gold" gap equal to whatever every player is simply holding in their pockets, which will look exactly like a dupe exploit every time you run it and isn't one.
  - **Online vs offline is NOT "the same approach" — offline is the hardest piece of this whole plugin, budget for it.** Online players: easy, `player.getInventory()` and `player.getEnderChest()` on the main thread. Offline players: **Bukkit/Paper does not expose an offline player's inventory or ender chest through the public API.** There is no `OfflinePlayer.getInventory()`. Reading it means either parsing the player's `.dat` NBT file yourself (e.g. with an NBT library) or dropping into version-specific NMS — and NMS is extra fragile now that Mojang mappings/de-obfuscation changed in 2026. Treat offline scanning as its own sub-task, not a footnote. Reasonable fallback if it proves too costly: make offline-player scanning **optional and off by default** (tie it to `includePlayerInventoriesInAudit`), scan only online players by default, and have the audit report state clearly that it excludes offline holdings so the gap is understood rather than mistaken for a dupe.
- **DoubleChest** — no extra work needed. When two Chest blocks sit adjacent, the Paper API already merges them into one `DoubleChest` inventory automatically; the scanner doesn't need special-case logic for large chests.
- **Container, BlockInventoryHolder, TileStateInventoryHolder, ChestBoat, ChestedHorse, AbstractHorse, AbstractVillager** — these are grouping interfaces in the API, not real placeable objects. They're already represented by the concrete types listed above (Chest/Barrel/etc., the named chest boat variants, Donkey/Mule/Llama, Horse/Camel/etc., and Villager/Wandering Trader respectively) — no separate handling needed.

**Ephemeral holders — do not scan, and do not hook events either:** Beacon, Anvil, and Grindstone can all hold a gold item for a moment, but none of them persist it in steady state, and none of them implement `InventoryHolder` in the Paper API. A beacon's payment slot consumes the item the instant a power is confirmed. An anvil/grindstone's material slot only exists while a player has the GUI open — close it and the contents return to the player. There is never a moment where "scanning the world" would find gold sitting in one of these. Per the "Reality check on those last three" note above, there's no dedicated Bukkit event for any of them either, and inferring consumption via `InventoryClickEvent` on the result/payment slot is fiddly and error-prone for something that essentially never fires on this all-Creative server. Consistent with that recommendation: **don't build custom hooks for these** (this codebase doesn't). Let the periodic `/cheese audit` account for any gold that leaves this way instead.

**Nested containers:** recurse into shulker boxes **and Bundles** (and any other container-holding-a-container) found inside another container's slots. Cap recursion depth (default 10) to guard against pathological NBT nesting.

**Bundles are a missing container type — add them.** A Bundle is a released item that can hold mixed item stacks, and gold ingots/nuggets stored in a bundle live nowhere else — not in any block, not as a loose stack. A bundle can sit in any container or any player pocket, so gold inside one is invisible to a scanner that only checks slots for `GOLD_INGOT`/`GOLD_NUGGET`. Read bundle contents via `BundleMeta.getItems()` and count the gold inside, everywhere you scan (containers, player inventories, ender inventories). Skip this and hiding gold in a bundle becomes a trivial way to make the audit undercount.

Output: a report of total units found, broken down by world and container category. **Do not auto-remove or auto-convert anything found by the scan** — it's gold already legitimately owned by players. Confiscating it automatically the first time someone runs a report command is a good way to make people furious. Removal should only happen through the explicit `/cheese remove` command below.

## Cap management commands (admin-only)

- `/cheese cap set <units>` — set the maximum allowed supply.
- `/cheese cap get` — show current cap and current tracked supply.
- `/cheese add <units> <player|x y z>` — mints new gold (ingots/nuggets, whichever combination is cleanest) into a player's inventory or a world location, **and simultaneously raises the cap by the same amount** (per the original design: adding Cheese adds to both the cap and the circulating supply, so the ratio of "used" to "allowed" stays meaningful).
- `/cheese remove <units> <player|x y z>` — destroys gold from a target and lowers the cap by the same amount.
- `/cheese audit` — runs a `--full` scan and compares it against the event-tracked `currentSupply`; reports any mismatch to the console/log rather than auto-correcting.

## Safety and permissions

Small trusted server, so this can stay light rather than enterprise-grade — but since everyone on the server is OP, permissions can't be gated with an `isOp()` check (that would just let everyone through). Use a real permission node instead:

- Register `cheese.admin` in `plugin.yml` with `default: false`:
  ```yaml
  permissions:
    cheese.admin:
      description: Access to /cheese cap, add, and remove
      default: false
  ```
  `default: false` means nobody has it automatically — not even OPs — until it's explicitly granted. Check it in code with `player.hasPermission("cheese.admin")`, never `player.isOp()`. **Because the entire security model hinges on this behavior, verify it once with a 30-second test: op a test account, do NOT grant `cheese.admin`, and confirm `/cheese cap set` is denied.** If the test account can run it, the node isn't registered correctly.
  - Register a second node `cheese.use` with `default: true` for the read-only commands (`/cheese scan --loaded`, `/cheese cap get`) so their permission state is intentional rather than implicit. `--full`, `add`, `remove`, and `cap set` all require `cheese.admin`.
- LuckPerms then handles the actual granting — `/lp user <name> permission set cheese.admin true` for whichever family members should be able to touch the cap. No LuckPerms-specific API integration needed in the plugin itself; it just manages standard Bukkit permission nodes, which the plugin checks normally.
- A simple console/log line for every cap change, add, and remove (who, what, when) is enough — no need for a dedicated audit-log file or reporting system.
- Skip claim/region-protection integration (WorldGuard, GriefPrevention) entirely unless you're actually running one of those plugins — not worth building for a group that already trusts each other.
- `--full` scan rate-limiting can be relaxed (or dropped) too, given a small world size on a small server — just keep it async/batched so it doesn't freeze the server for the second it takes to run, rather than worrying about someone spamming it.

## Build and testing setup (do this first)

The rest of this doc is "what to build." This section is "how to build it," which is otherwise unspecified and will cost the agent time or lead to guessed APIs.

- **Toolchain:** Gradle with a **Java 25 toolchain** (26.1 requires Java 25, so the server JVM is 25 — compile and run tests on JDK 25). Use a standard Paper plugin skeleton with `plugin.yml` (or `paper-plugin.yml`). Note that 26.1 also ships **fully unobfuscated** (no obfuscated variant), which is a further reason to stay on the public `paper-api` and avoid NMS where possible.
- **API dependency:** depend on the real `paper-api` artifact for this exact server version, and **compile against it so the compiler catches wrong class names.** For any class you're unsure exists (Copper Chest, Shelf, Decorated Pot container access), do **not** invent an API — leave a loud `// TODO: verify class name against paper-api` and let the build fail rather than shipping a guess.
- **SQLite:** shade `sqlite-jdbc` into the jar (shadowJar/relocation) and **serialize all writes** (single writer / one connection guarded by a lock), because the async scan and the command handlers can otherwise collide. Wrap the "raise cap + supply together" operation from `/cheese add` in a single transaction.
- **Testing:** use **MockBukkit** for unit tests of the pure logic — unit math (nugget/ingot ↔ units), the crafting-matrix gold-diff, cap enforcement, the seed calculation. The event/Creative behaviors can't be meaningfully unit-tested, so also write a short **manual test checklist** to run against a local Paper server: (1) op-without-permission is denied; (2) grabbing gold from the creative menu is blocked but moving already-owned gold is NOT; (3) middle-click pick-block of gold is blocked; (4) frame/stand/pot dupe is blocked; (5) `/cheese add` mints and raises the cap; (6) a `--full` audit after known changes reconciles.
- **Note on `/cheese add` minting:** mint by writing ItemStacks directly to the target inventory or `world.dropItem(...)` — do **not** simulate a creative-menu grab, or your own `InventoryCreativeEvent` block will cancel it.

## Config file

- `maxSupply` (units)
- `worlds` (list of worlds to include in scans — decide whether Nether/End count)
- `containerTypes` (toggle list so admins can exclude e.g. item frames or armor stands without a code change)
- `includePlayerInventoriesInAudit` (default true — separate toggle since reading every offline player's data file is more invasive and slower than scanning blocks, and some server owners may not want it)
- `blockGoldFromCreativeMenu` (default true — the `InventoryCreativeEvent` cancellation)
- `blockGoldDuplicationTricks` (default true — the Item Frame / Armor Stand / Decorated Pot cancellations)
- `fullScanCooldownSeconds` (fine to set low or 0 given the small server size)
- `nestedContainerMaxDepth`
- Storage backend path (SQLite file location)

## Open items to confirm once you're building

- Confirm the exact Paper API classes for Copper Chest and Shelf in your build. (These date to Java 1.21.9 / The Copper Age, autumn 2025 — not 26.1.2 — so the API has been stable for a while; just verify the class/interface names rather than assuming they mirror regular `Chest`.)
- Decide whether Nether/End worlds are in scope (gold generates differently there — zombified piglins are common in both, bastions only in Nether). Note the `worlds` config gates block/entity scans, but player and ender-chest scans are inherently global — don't accidentally filter those by the worlds list.
- Golden tools/armor and gold blocks: **decided above** — golden items are decremented out of the economy on craft (matrix diff) and not scanned; gold blocks are parked outside the economy (not scanned, block→ingots bypasses the cap). Only raw ingots/nuggets (and gold inside bundles/shulkers) count toward the tracked supply. Revisit only if you want golden equipment to count.
