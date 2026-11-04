<div align="center">

# SilentDrop
### The Silent Loot Revolution for Minecraft Servers

**No ground drops. No theft. No lag. Just instant, fair distribution.**

[![Version](https://img.shields.io/badge/version-1.1.0-blue.svg)](https://github.com/karonat1/SilentDrop/releases/tag/release)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.16.5--1.20.1-green.svg)](https://www.spigotmc.org/)
[![License](https://img.shields.io/badge/license-MIT-lightgrey.svg)](LICENSE.txt)
[![Java](https://img.shields.io/badge/Java-8%2B-orange.svg)](https://www.java.com/)

[Download](https://github.com/karonat1/SilentDrop/releases/tag/release) • [Spigot Page](https://www.spigotmc.org/resources/silentdrop.XXXXX/) • [Release](https://github.com/karonat1/SilentDrop/releases/tag/release)

</div>

---

## Why SilentDrop?

Your party just spent 20 minutes fighting a boss. It dies. Loot explodes everywhere.

❌ **Lag spikes** from hundreds of item entities spawning at once.

 **Thieves** steal the rare drop and log off immediately.

❌ **AFK leechers** get equal shares without contributing.

 **Chat spam** from pickup messages ruins the vibe.

**Vanilla loot is broken for multiplayer.** SilentDrop fixes it permanently by intercepting drops *before* they hit the ground and distributing them directly into inventories.

---

## ✨ Key Features

### ⚡ Zero Lag & Zero Clutter
- **No Item Entities:** Loot never spawns as physical items. Massive TPS boost on mob farms.
- **Silent Operation:** No pickup sound, no particles, no chat spam (unless you want it).
- **Conflict Safe:** Detects if another plugin touched the drops and steps back automatically.

### ⚔️ Smart Distribution (5 Modes)
Choose how loot is split per mob type or globally:
| Mode | Logic | Best For |
|------|-------|----------|
| `DAMAGE` | Proportional to damage + kill credit + VIP bonus | Competitive PvE, Bosses |
| `EQUAL` | Equal share for every participant | Co-op Survival, Friends |
| `RANDOM` | Lottery style — random participant gets each item | RPG, Unpredictability |
| `TOP` | Highest weight player gets everything | Solo-focused, Factions |
| `EVERYONE` | **Every** participant gets a full copy (multiplies loot!) | Custom events, Generous servers |

### 🛡️ Anti-Theft & Anti-Leech
- **Eligibility Filters:** Min damage %, max distance, time window, spectator skip.
- **Kill Credit:** Bonus weight for the final blow.
- **Permission Multipliers:** `silentdrop.multiplier.1.5` gives VIPs 1.5x share.
- **Drop Protection:** If an item *must* drop (overflow), it's locked to the owner. No hoppers, no mobs, no other players can steal it.

###  Personal Loot Vault (`/sd vault`)
**Inventory full?** No problem.
- Items overflow into a personal virtual chest instead of dropping on the ground.
- Access anytime with `/sd vault` — clean GUI with pagination.
- Admins can view/clear player vaults.
- Auto-save to `vault.yml` every 30 seconds.

### 🚜 Smart Anti-Farm Protection
Prevent abuse on mob farms while keeping legitimate gameplay:
- **Spawn Grace:** Mobs killed < X seconds after spawn drop vanilla (or nothing).
- **Skip Spawner/Baby/Named Mobs:** Fine-grained control over what gets distributed.
- **Rate Limiting:** Prevents inventory flooding during mass kills.

###  Per-Mob & Per-World Rules Engine
Define custom rules for specific mobs or regions. **First match wins.**
```yaml
rules:
  bosses:
    entities: [WITHER, ENDER_DRAGON]
    distribution: { mode: DAMAGE, min-players: 1 }
    eligibility: { kill-credit: 10.0, max-distance: 96.0 }
  
  farm-mobs:
    entities: [PIG, COW, SHEEP]
    anti-farm: { enabled: true, spawn-grace-seconds: 30 }
```
- Works with **built-in region boxes** (no WorldGuard required!).
- Inverted lists supported (apply to all EXCEPT these mobs).

### 🌍 Multi-Language Support
- Bundled: **English**, **Russian**, **Turkish**.
- Switch language in `config.yml` with one line: `language: ru`.
- Fully editable message files (`messages_ru.yml`, etc.).
- Add your own language by copying a template.

---

## 🚀 Quick Start

1. Download `SilentDrop-1.1.0.jar` from [Releases](https://github.com/karonat1/SilentDrop/releases/tag/release).
2. Place it in your server's `plugins/` folder.
3. Start/restart the server.
4. Edit `plugins/SilentDrop/config.yml` to match your needs.
5. Run `/sd reload` in-game or console.
6. Done! No resource pack needed.

### 🔍 Testing Without Killing Mobs
Use the built-in simulation command to see how loot would split:
```bash
/sd test DIAMOND:3 BONE:11 ROTTEN_FLESH:7
```
This shows participants, weights, percentages, and who gets what — without harming any mobs or giving items. Perfect for tuning configs.

---

##  Commands & Permissions

**Main Command:** `/silentdrop` (aliases: `/sd`, `/sdrop`)

| Command | Permission | Description |
|---------|------------|-------------|
| `/sd help` | - | Show subcommands |
| `/sd reload` | `silentdrop.admin` | Reload config & messages |
| `/sd test [ITEM:AMOUNT]` | `silentdrop.admin` | **Dry-run simulation** — see how loot would split |
| `/sd stats` | `silentdrop.admin` | Live diagnostics: distributions, fallbacks, errors, vault size |
| `/sd vault` | `silentdrop.use` | Open your personal loot vault GUI |
| `/sd vault <player>` | `silentdrop.admin` | View another player's vault |
| `/sd give <player> <item> [amt]` | `silentdrop.admin` | Give item using SilentDrop logic (tests overflow) |
| `/sd clear [player/all]` | `silentdrop.admin` | Clear vaults |

**Permissions:**
- `silentdrop.use` (default: `true`) — Use vault, receive loot.
- `silentdrop.admin` (default: `op`) — All commands.
- `silentdrop.bypass` — Skip distribution entirely (vanilla drops for this player).
- `silentdrop.bypass.antifarm` — Ignore anti-farm checks.
- `silentdrop.multiplier.<number>` — Share multiplier (e.g., `silentdrop.multiplier.2.0`).

---

## ⚙️ Configuration Highlights

Everything is tunable in `config.yml`. Here's what you can control:

- **Distribution:** Mode, min-players, split-stacks, solo-killer-gets-all.
- **Eligibility:** Damage %, distance, time window, item blacklist, item chance.
- **Overflow:** VAULT / DROP / VOID actions.
- **Anti-Spam:** Rate limit distributions per player (prevents inventory flood).
- **Experience:** DISABLED / KILLER / TOP_SHARE / SPLIT modes.
- **Tracker:** Damage window, max contributors per mob.
- **Limits:** Global distributions per tick (TPS protection).

> **Full Config Reference: See comments in generated config.yml.

---

## 🛠️ For Developers

### Building from Source
```bash
git clone https://github.com/karonat/SilentDrop.git
cd SilentDrop
mvn clean package
```
- **Requirements:** JDK 8+, Maven 3.6+.
- **Output:** `target/SilentDrop-1.1.0.jar`.
- **Tests:** 41 unit tests covering allocation math, inventory logic, and language files. Run with `mvn test`.

### Project Structure
```
src/main/java/ru/silentdrop/
├── SilentDropPlugin.java       # Entry point, metrics, tasks
├── command/                    # /sd commands + tab-complete
├── config/                     # Settings, Rules, Messages (i18n)
├── listener/                   # Death, Combat, ItemOwnership events
├── loot/                       # Distributor, Allocator, DropProtector
├── track/                      # DamageTracker (arrows, TNT, pets)
├── vault/                      # LootVault GUI + storage
└── util/                       # Text, ItemCodec, InventoryMath
```

### API & Hooks
SilentDrop is designed to be conflict-safe:
- Uses `LOWEST` priority on `EntityDeathEvent` to intercept drops first.
- Checks `skip-if-drops-tampered` to avoid conflicts with other loot plugins.
- Atomic UUID locking prevents double-processing.
- All exceptions are caught and logged; drops fall back to vanilla on error.

---

##  Known Limitations (Honesty First)

- Loot goes to **main 36 inventory slots**. Armor/offhand are not auto-equipped (vanilla behavior).
- `overflow.action: DROP` is the only case where items appear in the world.
- `mode: EVERYONE` **multiplies** items — enable consciously.
- Spawn time is only known for mobs spawned while the plugin is running (unless `require-known-spawn-time: false`).
- Damage tracker resets on plugin reload (data is in-memory for performance).

---

## 📜 License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE.txt) file for details.

**Free to use, modify, and distribute.** No telemetry beyond anonymous bStats. No backdoors. Clean code.

---

<div align="center">

**Made with ❤️ for the Minecraft community.**
If SilentDrop helps your server, please leave a review on [SpigotMC](https://www.spigotmc.org/resources/silentdrop.XXXXX/)!

</div>