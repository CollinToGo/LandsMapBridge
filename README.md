# Lands Map Bridge

Shows [Lands](https://www.spigotmc.org/resources/53313) claims on Xaero's Minimap and Xaero's World Map, and lets you claim or unclaim chunks straight from the world map.

It has two parts:

| Part | Runs on | File |
|------|---------|------|
| Fabric mod | Client (Minecraft 26.3) | `mod/build/libs/landsmapbridge-fabric-<version>.jar` |
| Paper plugin | Server (Paper 26.3 with Lands) | `plugin/build/libs/landsmapbridge-paper-<version>.jar` |

The mod is optional for players: anyone can join the server without it. The plugin only talks to clients that have the mod.

## Features

- Claims on the minimap and the world map, coloured by how you relate to the land:
  - green: your own land
  - blue: a land you are trusted in
  - yellow: an ally
  - red: an enemy or a land you are at war with
  - grey: everyone else
- Land name and owner when you hover a claim on the world map (and in the minimap info lines).
- Live updates when anyone claims, unclaims, renames, trusts or changes relations.
- Right-click (or right-click and drag a rectangle) on the world map, then pick **Lands: Claim** or **Lands: Unclaim**. A confirmation screen shows the chunk count first.
- Claims go into the land you are editing (`/lands edit <land>`). If you only have one land, that one is used.

## Rules

Claiming uses a Lands selection, the same path as the Lands claim wand. Costs, chunk limits, connected-claim rules, distance rules and role flags all come from your Lands config.

Unclaiming needs the `lands.command.unclaim` permission and the "claim" role flag in the land. Claiming additionally needs `lands.command.claim`.

You can only claim in the dimension you are currently in.

## Installation

1. Server: put the plugin jar into `plugins/` next to Lands and restart.
2. Client: install Fabric Loader, Fabric API, Xaero's Minimap and Xaero's World Map, then put the mod jar into `mods/`.

## Plugin config

`plugins/LandsMapBridge/config.yml`:

```yaml
# Largest rectangle (in chunks) a player may claim or unclaim at once from the map.
max-claim-area: 256

# Full re-check of all claims, catches changes Lands sends no event for.
resync-seconds: 30
```

## Building

Requires nothing but a JDK; Gradle downloads Java 25 by itself.

```bash
./gradlew build
```

`./gradlew :mod:runClient` starts a development client with both Xaero maps.

## How it works

- `common/` holds the small wire protocol shared by both sides (plain plugin messages on `landsmapbridge:*` channels).
- The plugin keeps a snapshot of all claims and sends only the differences to mod clients.
- The mod hooks a highlighter into Xaero's minimap and world map (the same system Open Parties and Claims uses) and adds its options to the world map's right-click menu.
