<div align="center">

# MaceSurvival

**A dedicated-server mace battle royale built for Paper.**

[![Status](https://img.shields.io/badge/status-pre--alpha-E89B36)](https://github.com/QiYuan-Origin/MaceSurvival)
[![Paper](https://img.shields.io/badge/Paper-1.21.11-49A3D8)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-21-E76F00)](https://adoptium.net/)
[![License](https://img.shields.io/github/license/QiYuan-Origin/MaceSurvival)](LICENSE)

</div>

MaceSurvival is a large-scale PvP game mode centered on maces, aerial deployment,
tiered world loot, team play, temporary equipment, and high-mobility combat. It is
designed as the only game running on a dedicated server: joining players enter the
waiting lobby automatically, while players who arrive during an active match become
spectators.

> [!IMPORTANT]
> MaceSurvival is currently in the design and bootstrap stage. This page describes
> the agreed gameplay target, not a finished public release. Rules and balance may
> still change as the full design is discussed.

## Inspiration

The initial direction is inspired by the gameplay shown in
[Pro Players VS Mace Battle Royale](https://www.youtube.com/watch?v=m8j_BFNOM-o),
with a [Chinese subtitled version on Bilibili](https://www.bilibili.com/video/BV15k3q6bEgs).
The source video demonstrates the core tier progression: one-star chests offer
early utility such as golden apples and ender pearls, two-star chests can provide
elytra and spears, and three-star chests contain high-value rewards such as armor
upgrades and Totems of Undying.

This repository is an independent recreation. It does not contain code, fonts,
textures, maps, or other assets from `mace.vip`, `mcpvp.club`, or the referenced
video.

## Match Flow

| Phase | Planned behavior |
| --- | --- |
| Join | Enter the waiting lobby immediately, or spectator mode if a match is already active. |
| Waiting | Use lobby hotbar controls to create, join, and manage a team before the round. |
| Transition | A short blackout hides the move from the lobby to the live map. |
| Deployment | Drop from high above the map with server-controlled vertical momentum. Looking around steers only horizontal travel. |
| Scavenge | Search the surface for randomly placed one-, two-, and three-star chests. |
| Fight | Upgrade equipment, combine damaged loot, and eliminate enemy players or teams. |
| Elimination | The defeated player's equipment bursts out into the world as collectible loot; the player becomes a spectator. |
| Finale | The surviving winner or team receives a dedicated end title and layered sound sequence. |

The exact player limit, team size, match countdown, border behavior, and victory
rules will be finalized with the remaining game design.

## Starting Loadout

Every active player starts with four infinite-durability netherite weapons. All
hotbar positions are configurable.

| Hotbar slot | Default item | Durability |
| ---: | --- | --- |
| 1 | Netherite Sword | Infinite |
| 2 | Netherite Axe | Infinite |
| 8 | Mace | Infinite |
| 9 | Mace | Infinite |

These permanent starting weapons are separate from the limited-use equipment found
during the match.

## Tiered Chests

One-, two-, and three-star chests spawn at valid random surface locations. Every
chest has a world-space `TextDisplay` tier label using vertical billboard behavior,
keeping the text readable as a player changes direction.

The planned loot pool includes:

- Upgrade and enchantment books
- Elytra
- Lunge Spears, distinct from tridents
- Food and direct healing items
- Totems of Undying
- Wind Charges and Ender Pearls
- Shields
- Potions
- Armor and equipment upgrades

Higher tiers produce stronger and rarer rewards. Loot weights, refill behavior, and
per-tier tables will be configurable once their balance is locked.

## Limited-Use Equipment

Selected loot has a small effective durability pool. After its final use, it breaks
with an explosion effect. Two compatible damaged items can be combined directly in
the player's inventory using the following rule:

```text
result durability = target durability + (consumed durability / 2)
```

The result cannot exceed that item's configured maximum durability. The starting
sword, axe, and maces do not use this system.

## Upgrade Books

Upgrade books are divided into two interaction models.

### Instant Upgrades

Some books disappear as soon as they enter the inventory and apply their effect to
the player. Planned examples include increased damage, improved healing, and a bonus
triggered by the player's next elimination.

### Weapon Upgrades

Other books remain in the inventory until the player applies them to a compatible
weapon from the inventory interface. Planned upgrades include:

- Storm
- Sharpness
- Armor Piercing
- Density
- Unbreaking
- Kill Mending

Kill Mending replaces experience-based repair with durability restored by player
eliminations. Compatibility, stacking, levels, and repair values will be defined by
configuration rather than hard-coded balance.

## Text System

Most bundled in-game text will be English. Every configurable text surface is
planned to use one unified component pipeline with support for:

- Ampersand legacy codes (`&`)
- Section-sign legacy codes (`§`)
- MiniMessage
- Raw JSON text components

The visual direction takes inspiration from `mcpvp.club`: compact typography,
strong scoreboard hierarchy, and deliberate text shadows. A scoreboard title may,
for example, use a MiniMessage expression such as:

```text
<gradient:#FFFFFF:#FFFFFF><shadow:#404040:1>[R]</shadow></gradient>
```

Legacy input will be converted into Adventure components before display so raw color
markers are never shown to players. JSON input will be parsed as components instead
of being emitted as literal text.

## Resource Pack

A custom resource pack is planned for the final fonts and visual identity, but it is
intentionally outside the current development phase. No resource pack is included
yet; the core match systems will be implemented and stabilized first.

## Current State

The repository currently provides the Java 21 / Paper 1.21.11 plugin foundation and
build configuration. Match states, lobby tools, teams, deployment, loot, spectators,
combat upgrades, configuration, and rich-text rendering are the next implementation
work and are not playable yet.

## Building

Requirements:

- Java 21
- Maven 3.9 or newer

```shell
mvn clean package
```

The generated JAR is written to `target/`. At the current pre-alpha stage, a
successful build validates only the plugin bootstrap.

## License

MaceSurvival is free software licensed under the
[GNU General Public License v3.0](LICENSE).
