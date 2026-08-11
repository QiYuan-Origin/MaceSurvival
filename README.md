<div align="center">

# MaceSurvival

**A dedicated-server mace battle royale built for Paper.**

[![Status](https://img.shields.io/badge/status-active_development-2F9E6F)](https://github.com/QiYuan-Origin/MaceSurvival)
[![Paper](https://img.shields.io/badge/Paper-1.21.11-49A3D8)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-21-E76F00)](https://adoptium.net/)
[![License](https://img.shields.io/github/license/QiYuan-Origin/MaceSurvival)](LICENSE)

</div>

MaceSurvival is a server-side battle royale centered on maces, aerial deployment,
shared world loot, equipment upgrades, and a continuously moving final circle. It
is built as the only game running on a dedicated Paper server. Players who connect
before deployment enter the waiting lobby; players who connect after the match has
started enter spectator mode.

## Inspiration

The mode is inspired by the gameplay shown in
[Pro Players VS Mace Battle Royale](https://www.youtube.com/watch?v=m8j_BFNOM-o),
with a [Chinese subtitled version on Bilibili](https://www.bilibili.com/video/BV15k3q6bEgs).

This repository is an independent recreation. It does not contain code, fonts,
textures, maps, or other assets from `mace.vip`, `mcpvp.club`, or the referenced
video.

## Match Flow

| Phase | Behavior |
| --- | --- |
| Join | Enter the waiting lobby immediately, or spectator mode if a match is already active. |
| Waiting | Use lobby hotbar controls to manage a team and starting hotbar layout. The normal countdown begins at 100 players and lasts 120 seconds; an administrator can force the same flow with any player count. |
| Transition | A short blackout moves participants from the void lobby into a random-seed amplified match world. |
| Deployment | Each separated team rides a Happy Ghast. The leader steers, may drop the team during the first 15 seconds, and everyone is forced off after 30 seconds. |
| Descent | Looking around controls horizontal travel while the server keeps vertical momentum fixed. Deployment fall damage is disabled. |
| Scavenge | Search the surface for shared one-, two-, and three-star chests containing equipment, healing, mobility, enchantments, and upgrades. |
| Fight | Upgrade weapons, automatically equip armor, combine eligible damaged equipment, and eliminate enemy teams. Friendly attacks deal no damage. |
| Finale | The circle reaches a 24-block radius and keeps moving until the remaining players resolve the fight. The winner receives an end title and sound sequence. |

Teams support one to four players. There is no configured maximum match population.
The normal circle schedule lasts 25 minutes; multiplayer matches can end earlier
when only one team remains, while an administrator-started solo match still runs
the full schedule before resolving.

## World And Circle

Each match uses a random-seed amplified world with a 5,000-block hard radius.
Participants cannot break or place blocks. The circular safe zone contracts through
the following radii:

```text
3000 -> 2000 -> 1000 -> 650 -> 280 -> 80 -> 24
```

Every target circle is placed inside the previous circle. Once the final radius is
reached, its center continues choosing random targets and moving. Red particles
show the current boundary, with a darker treatment for players outside it.

## Starting Loadout

Every participant starts with four unbreakable weapons. Their hotbar positions can
be changed in the lobby and have configurable defaults.

| Hotbar slot | Default item | Durability |
| ---: | --- | --- |
| 1 | Netherite Sword | Infinite |
| 2 | Netherite Axe | Infinite |
| 8 | Mace | Infinite |
| 9 | Mace | Infinite |

## Shared Tiered Chests

One-, two-, and three-star chests spawn at valid random surface locations. Every
chest has a world-space `TextDisplay` tier label using vertical billboard behavior,
shadowed uniform-font stars, and four to eight items in random slots.

Chests are shared by the entire server. The active target is eight chests per living
player. Chests outside the next circle are removed during contraction, and the safe
area is replenished after a contraction completes. After the last viewer closes an
opened chest, it disappears three seconds later with red particles and a layered
sound; any contents left inside drop at the block position.

The configurable loot table includes:

- Upgrade and enchantment books
- Elytra
- Lunge Spears, distinct from tridents
- Food and direct healing items
- Totems of Undying
- Wind Charges and Ender Pearls
- Shields
- Potions
- Armor and equipment upgrades

Every configured entry has a non-zero chance in every tier. Higher stars change the
relative probabilities rather than making whole categories unavailable.

## Equipment And Fusion

Spears, shields, and elytra receive a limited random use range and consume durability
through their normal actions. They break through vanilla durability behavior after
their final use. Compatible damaged items can be combined in the inventory; the
consumed item contributes half of its remaining durability. An item can participate
in one fusion per elimination, and that allowance refreshes after the player earns a
kill.

Extra spears, shields, and elytra are stored in Reserve Bundles. Each bundle holds
12 unstackable equipment items, and a player may carry multiple bundles. Bundle
contents can be thrown back into the world but cannot be moved into ordinary
inventory slots as loose duplicates.

Armor loot is equipped automatically and cannot be manually removed. Replaced armor
returns to the chest; lower-tier armor remains there and drops if the chest expires.
Armor enchantment loot is applied when the armor is acquired rather than appearing
naturally on generated pieces.

## Upgrade Books

Upgrade books are divided into two interaction models.

### Instant Upgrades

Damage, healing, and next-kill boost books disappear when collected and apply their
effect for the rest of the match. Collection feedback is shown immediately.

### Weapon Upgrades

Weapon books remain until applied to a compatible weapon from the inventory. The
table includes Sharpness, Breach, Density, Wind Burst, Unbreaking, and Kill Mending.
Configured maximum levels extend beyond vanilla levels, while Thorns is capped at
level IV.

Kill Mending replaces experience repair with elimination repair. Its three levels
restore 1%, 3%, or 5% of maximum durability, divided across damaged items in the
player's inventory.

When a player dies, carried equipment drops into the world. Their sword, axe, and
maces can merge into the collector's matching starter weapons. Upgrade levels are
only replaced when the defeated weapon carries a higher level, so collecting weaker
gear never downgrades the surviving weapon.

## Elimination And Reconnection

Eliminated players become spectators, and players joining after deployment also
spectate the active match. A disconnected participant has a 240-second reconnect
window. When that window expires, their inventory is dropped at the disconnect
location and later joins remain in spectator mode.

Kill credit uses the direct killer or the most recent valid attacker within 30
seconds. Lifetime games, wins, kills, deaths, and best placement are stored in YAML
and exposed through `/macesurvival stats` and PlaceholderAPI.

## Text System

Most bundled in-game text is English. Configurable text uses one Adventure component
pipeline with support for:

- Ampersand legacy codes (`&`)
- Section-sign legacy codes (`§`)
- MiniMessage
- Raw JSON text components
- PlaceholderAPI placeholders when the plugin is installed

The scoreboard uses a compact uniform-font title with a text shadow. It shows match
time and circle state on one line, living players, personal kill rank, surviving
team kill rank, and teammate direction/distance markers. Eliminated teammates remain
visible with red strikethrough styling.

## Commands And Permissions

The main command is `/macesurvival` with `/ms` and `/mace` aliases. Player commands
cover help, team management, loadout settings, and statistics. Administrators can
force-start or stop a match, reload configuration, and set the lobby. All branches
provide tab completion, and administrator branches are hidden from players without
their corresponding `macesurvival.admin.*` permission. LuckPerms can manage these
standard Bukkit permissions.

## Resource Pack

A custom resource pack has not been made and is not included in this repository.
The current build uses vanilla fonts, `TextDisplay` entities, particles, sounds, and
items as its fallback presentation. The server plugin does not require a client mod.

## Configuration

Defaults are bundled in `src/main/resources`:

- `config.yml` controls server flow, teams, deployment, borders, loadout, combat,
  loot placement, and shutdown behavior.
- `loot.yml` defines materials, typed rewards, amount/use ranges, and tier weights.
- `messages.yml` contains chat, title, action-bar, item, and scoreboard text.
- `menus/` contains the configurable team and loadout inventory menus.

`/macesurvival reload` restores missing bundled configuration files before loading
their current values.

## Building

Requirements:

- JDK 21

Linux and macOS:

```bash
./gradlew build
```

Windows:

```powershell
gradlew.bat build
```

The Shadow JAR is written to `build/libs/MaceSurvival-<version>.jar`. Copy it into a
Paper 1.21.11 server's `plugins` directory. PlaceholderAPI and LuckPerms are optional
integrations, not hard dependencies.

## License

MaceSurvival is free software licensed under the
[GNU General Public License v3.0](LICENSE).
