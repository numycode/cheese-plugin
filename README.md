# cheese-plugin

A Paper **26.1.2** plugin that manages a physical currency called **Cheese** on a small private server.

Cheese isn't a custom item — it **is** vanilla **Gold Ingots and Gold Nuggets** (a client-side resource pack reskins them; the plugin doesn't touch textures). The plugin's whole job is to track how much gold "value" exists in the world and enforce a hard supply cap on it, so the currency stays scarce.

Everyone on the server plays in **Creative mode**, which is what makes this non-trivial: any player can normally pull unlimited gold out of the Creative menu, and Creative's "placing an item doesn't consume it" behavior turns one ingot into infinite gold via item frames, armor stands, and decorated pots. The plugin closes those vectors while leaving legitimately-granted gold fully usable.

## Units

All accounting is done in a single integer unit to avoid ingot/nugget rounding:

- **1 nugget = 1 unit**
- **1 ingot = 9 units**

`currentSupply` (how much is in circulation) and `maxSupply` (the cap) are both tracked in units and persisted in SQLite.

## What it does

- **Seeds the supply on first run.** On the very first startup it runs one full economy scan and sets `currentSupply` to the total gold it finds, so the tracker and the world agree from day one. (Event tracking only knows about gold that moves *after* the plugin is running, so this step is not optional.)
- **Blocks Creative-menu grabs** of Gold Ingots/Nuggets (`InventoryCreativeEvent`) while still allowing a player to rearrange gold they already hold. A rate-limited action-bar message explains the block.
- **Blocks the Creative duplication tricks** — placing gold into an empty item frame, onto an armor stand, or into a decorated pot.
- **Enforces the cap on survival gold sources** as cheap insurance (smelting, Zombified Piglin drops, natural loot chests) and **tracks gold leaving circulation** (Piglin bartering, and gold consumed by crafting into derived items via a crafting-matrix diff). Gold in **block** form is intentionally parked outside the tracked economy — blocks aren't counted, and crafting a block back into ingots always bypasses the cap.
- **Scans and audits.** `/cheese scan` reports the total gold found, broken down by world and container category. `/cheese audit` runs a full scan and reports any drift from the tracked supply — it never auto-corrects, since a mismatch could have an innocent cause.

Gold hidden inside **Bundles** and **Shulker Boxes** is counted and enforced everywhere gold is, including nested containers (up to a configurable depth).

## Commands

| Command | Permission | Description |
|---|---|---|
| `/cheese scan [--loaded]` | `cheese.use` | Scan currently-loaded chunks (default). Fast, safe anytime. |
| `/cheese scan --full` | `cheese.admin` | Async, batched sweep that also covers unloaded chunks on disk. |
| `/cheese cap get` | `cheese.use` | Show the current tracked supply and cap. |
| `/cheese cap set <units>` | `cheese.admin` | Set the maximum allowed supply. |
| `/cheese add <units> <player\|x y z>` | `cheese.admin` | Mint gold to a player or location **and** raise the cap by the same amount. |
| `/cheese remove <units> <player\|x y z>` | `cheese.admin` | Destroy gold from a target **and** lower the cap by the same amount. |
| `/cheese audit` | `cheese.admin` | Full scan compared against the tracked supply; reports mismatches without correcting them. |

`add`/`remove` only ever mint or take **whole** ingots/nuggets — an ingot is never silently split to hit an exact unit count, and `remove` reports how much it actually removed if the target held less than requested.

## Permissions

The server model is "everyone is OP," so permissions are **not** gated on OP status — they use real permission nodes instead:

- **`cheese.admin`** — `default: false`. Nobody has it automatically, not even OPs, until it's explicitly granted. Covers `cap set`, `add`, `remove`, `scan --full`, and `audit`.
- **`cheese.use`** — `default: true`. Covers the read-only commands (`scan --loaded`, `cap get`).

The **server console** bypasses these checks entirely — console/RCON access is already full control of the machine (including the database file), so gating it in-game would add friction without adding security.

Grant admin access to a trusted user with LuckPerms:

```text
/lp user <name> permission set cheese.admin true
```

No LuckPerms-specific integration is needed in the plugin — it just checks standard Bukkit permission nodes.

## Configuration

`config.yml` (defaults shown):

| Key | Default | Meaning |
|---|---|---|
| `maxSupply` | `8100` | Cap used **only** for the first-run seed. After seeding, the live cap lives in SQLite and this value is no longer read. |
| `worlds` | `world`, `world_nether`, `world_the_end` | Worlds included in block/entity scans. Player and ender-chest scans are always global and are **not** filtered by this list. |
| `includePlayerInventoriesInAudit` | `true` | Whether scans read online players' carried inventories and ender chests. (Offline players are not scanned — see below.) |
| `nestedContainerMaxDepth` | `10` | Recursion cap for nested containers (Bundles, item-form Shulker Boxes). |
| `blockGoldFromCreativeMenu` | `true` | Cancel Creative-menu grabs of gold. |
| `blockGoldDuplicationTricks` | `true` | Cancel the item frame / armor stand / decorated pot dupes. |
| `fullScanCooldownSeconds` | `3600` | Minimum seconds between `--full` scans / audits. Safe to lower toward `0` on a small world. |
| `storage.file` | `cheese.db` | SQLite database filename inside the plugin's data folder. |

## Building

Requires **JDK 25** (Minecraft 26.1 runs on Java 25).

```bash
./gradlew build
```

The single deployable artifact is:

```text
build/libs/cheese-plugin-0.1.0.jar
```

It's a shaded jar with `sqlite-jdbc` bundled and relocated. Drop it into your server's `plugins/` folder and restart. (The plain `jar` task is deliberately disabled so there's no unshaded, non-functional jar to grab by mistake.)

## Requirements

- Paper **26.1.2**
- Java **25**

## Notes and known gaps

- **Offline-player holdings are not scanned.** Bukkit/Paper exposes no public API for an offline player's inventory or ender chest, so audits reflect online players only. The gap is expected, not a dupe.
- Some behaviors can only be validated with a connected Minecraft client (Creative-menu grab vs. rearrange, middle-click pick-block, the frame/stand/pot dupe blocks in practice). The scan, seeding, commands, and permission model have been verified against a real Paper 26.1.2 server.
