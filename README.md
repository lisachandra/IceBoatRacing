# ❄️ IceBoatRacing

> **A professional-grade Minecraft Paper plugin for competitive ice boat racing** — featuring precision checkpoint detection, live scoreboards, automatic track generation, mandatory pit stops, multi-language support, and a full race management system.

[![Modrinth](https://img.shields.io/modrinth/dt/ibr?label=Modrinth%20Downloads&color=00AF5C&logo=modrinth)](https://modrinth.com/plugin/ibr)
[![CurseForge](https://img.shields.io/curseforge/dt/1474021?label=CurseForge%20Downloads&color=F16436&logo=curseforg-)](https://www.curseforge.com/minecraft/bukkit-plugins/ice-boat-racing)
[![Paper](https://img.shields.io/badge/Paper-1.21.4%2B-FF4444?logo=paper)](https://papermc.io)
[![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk)](https://openjdk.org)
[![License](https://img.shields.io/github/license/mattmunichYT/IceBoatRacing?color=blue)](LICENSE)
[![Version](https://img.shields.io/badge/Version-v2.2--RELEASE-light)](https://github.com/mattmunichYT/IceBoatRacing/releases)

---

## 📋 Table of Contents

- [Overview](#-overview)
- [Key Features](#-key-features)
- [Feature Showcase](#-feature-showcase)
- [Requirements](#-requirements)
- [Installation](#-installation)
- [Commands](#-commands)
- [Permissions](#-permissions)
- [Configuration](#-configuration)
- [How It Works](#-how-it-works)
- [Examples & Usage](#-examples--usage)
- [FAQ](#-faq)
- [Developer API](#-developer-api)
- [Building from Source](#-building-from-source)
- [Contributing](#-contributing)
- [Reporting Issues](#-reporting-issues)
- [License](#-license)
- [Credits](#-credits)

---

## 🎯 Overview

**IceBoatRacing** transforms your Minecraft server into a competitive ice boat racing platform. Built for **Paper 1.21.4+** with **Java 21**, the plugin provides everything needed to create, manage, and run professional ice boat races:

- **Interactive race creation wizard** — step-by-step guided setup
- **Precision checkpoint system** — two geometry modes (BOX legacy / PLANE modern) with alternate routes
- **AutoTrace** — drive a lap and let the plugin generate oriented-plane checkpoints automatically
- **Mandatory pit stops** — timed pit boxes with a live countdown, team-livery colors, and automatic disqualification for skipped stops
- **Live scoreboard** — real-time per-player sidebar during races
- **Race lights** — configurable stained-glass starting lights (5-4-3-2-1-GO!)
- **Sector timing** — split times for detailed performance analysis
- **Car/boat registration** — assign boats to players with custom names and types
- **Multi-language support** — English & French (extensible via YAML)
- **Full persistence** — each race stored as a single YAML file

Designed for competitive servers like **Grands Prix by Mini Jeux Entre Potes**.

---

## ✨ Key Features

| Feature                 | Description                                                                                                                                   |
|-------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| 🏁 **Race Lifecycle**   | Create → Prepare → Start (with countdown lights) → Live tracking → Finish with full standings                                                 |
| 📍 **Checkpoints**      | BOX (axis-aligned cuboid) or PLANE (oriented rectangle) — PLANE supports curved/diagonal tracks                                               |
| 🔄 **Alternate Routes** | Add bypass gates to any checkpoint (pit lanes, spectator bypasses)                                                                            |
| 🎯 **Sectors**          | Intermediate timing gates for split analysis                                                                                                  |
| 🤖 **AutoTrace**        | Record a lap → plugin generates PLANE checkpoints with track-surface detection                                                                |
| 🅿️ **Pit Stops**         | Configurable mandatory pit-stop count per race, timed pit boxes with countdown + team-color themes, automatic DSQ for non-compliant finishers |
| 🚤 **Car System**       | Register boats per player (all wood types + rafts + chest variants), custom names, clickable management                                       |
| 📊 **Live Sidebar**     | Real-time position/lap/progress per player during race                                                                                        |
| 🏁 **Race Lights**      | Optional 6-stage stained glass starting lights (Brown→Red→Orange→Yellow→Lime→Green)                                                           |
| 🌍 **i18n**             | English & French built-in; MiniMessage formatting with `%placeholder%` substitution                                                           |
| 💾 **Persistence**      | One YAML file per race in `plugins/IceBoatRacing/races/`                                                                                      |

---

## 🎪 Feature Showcase

### 🏁 Race Creation Wizard (`/race create`)

An interactive, title-guided wizard walks you through:

1. **Name the race** — unique identifier
2. **Set lap count** — how many laps to finish
3. **Define track?** — jump to AutoTrace or skip
4. **AutoTrace** — drive a full lap; plugin generates checkpoints
5. **Define cars?** — register participant boats
6. **Save** — race persisted to YAML

> All steps are optional — you can define track/cars later via `/checkpoint autotrace` and `/car create`. Pit boxes and required pit-stop count are configured separately after race creation, via `/pitbox create` and `/race setPitStops` (not currently part of the wizard).

### 📍 Checkpoint System

#### Two Geometry Modes

| Mode               | Shape                                                       | Use Case                          | Creation                                                                         |
|--------------------|-------------------------------------------------------------|-----------------------------------|----------------------------------------------------------------------------------|
| **BOX** (legacy)   | Axis-aligned cuboid (min/max corners)                       | Simple straight tracks            | Wooden shovel left/right click two blocks → `/checkpoint create <race> [id]` |
| **PLANE** (modern) | Oriented rectangle (center + normal + halfWidth/halfHeight) | Curved, diagonal, elevated tracks | **AutoTrace** or manual via YAML                                                 |

> **PLANE checkpoints** use precise double-precision centers and ray-plane intersection for crossing detection — far more reliable on non-axis-aligned tracks.

#### Checkpoint Types

| Type           | Purpose                      | AutoTrace Output                                  |
|----------------|------------------------------|---------------------------------------------------|
| `START_FINISH` | Lap counter + race start/end | First generated checkpoint (cardinal-snapped)     |
| `NORMAL`       | Standard lap progression     | All intermediate checkpoints                      |
| `SECTOR`       | Split timing (optional)      | Added manually via `/checkpoint autotrace sector` |

#### Alternate Routes (Bypass Gates)

Attach secondary physical gates to any checkpoint — crossing **either** counts. Perfect for:
- Pit/stands lanes that rejoin past the start/finish line
- Shortcut routes that merge back
- Spectator bypass corridors

```bash
# Select two corners with wooden shovel, then:
/checkpoint addAlternate <raceName> <checkpointID>
```

### 🤖 AutoTrace — Drive, Don't Place

```
/checkpoint autotrace start <raceName> [spacing] [halfWidth] [halfHeight] [loop|noloop]
```

1. **Start recording** — configurable point spacing (default 3 blocks)
2. **Drive one full lap** — boat or on foot
3. **Auto-stops** when you return near the start (configurable threshold)
4. **Preview** — particle outlines (Flame=Start/Finish, End Rod=Normal, Villager=Sector, plus the
   smoothed centreline). Turns on by itself after an edit
5. **Edit** — resize/delete individual checkpoints by clicking near them
6. **Add sectors** — mark split gates with shovel
7. **Accept** — batch-saves all as PLANE checkpoints in one YAML write

**Under the hood:**
- Ramer-Douglas-Peucker simplification (ε=0.4)
- Even arc-length resampling
- Track-surface recentering (scans for ice/packed ice/blue ice)
- Per-checkpoint width measurement
- Cardinal snapping for start/finish & sectors

### 🚤 Car/Boat Registration

```
/car create
```

1. **Break a block** — sets spawn location (block +1 Y)
2. **Enter owner name** — offline players supported
3. **Hold a boat item** — any wood type, raft, or chest variant
4. **Select race** — clickable list if not in RaceCreator flow

**Supported boat types:** Oak, Spruce, Birch, Jungle, Acacia, Dark Oak, Mangrove, Cherry, Bamboo, Pale Oak + all chest variants.

Cars spawn when the race starts — and only for players who joined the registration
intermission. Owners who never joined are not teleported or moved into a boat. Spawned boats
are invulnerable, get the configured custom name, and rotate to `race.startRotation`.

### 📊 Live Scoreboard

Each racer gets their **own** scoreboard with a sidebar objective; the server's main
scoreboard is never touched, so non-racers never see a race sidebar. It updates as the racer
progresses and shows:
- Current lap / total laps
- Checkpoint progress within lap
- Pit stops completed / required

The sidebar is removed (and the player's previous scoreboard restored) when they finish,
leave, or when the race ends.

### 🏁 Race Lights (Optional)

Configure a 3D region in `config.yml` (`race.lights.from` → `race.lights.to`). On `/race start`:
```
5 (Dark red) → 4 (Red) → 3 (Orange) → 2 (Yellow) → 1 (Lime) → GO! (Green)
```
Each stage fills the region with the corresponding stained-glass, synced with title countdown and pling sounds.

### 🅿️ Pit Boxes (Mandatory Pit Stops)

Each race can require racers to complete a configurable number of pit stops before finishing counts as valid — enforced end-to-end, from trigger detection through final standings.

**Setup (`/pitbox create <race>`):**

1. **Name the pit box** — for identification in `/pitbox list`
2. **Define location** — select 2 corners with the wooden shovel wand (reuses the same wand as `/checkpoint create`), then confirm
3. **Set duration** — seconds the stop takes
4. **Set allowed players** — specific names, or `*` for anyone racing
5. **Pick a color** — solid or gradient (see below)

**At race time:**

- Pit box detection is folded into the same `PlayerMoveEvent` loop used for checkpoint crossing — no separate listener re-walking every racing boat each tick.
- Entering an eligible, unoccupied box freezes the boat (snapped back on every move) for the box's duration — the stop can't be skipped: exiting is allowed but just hands the driver a fresh boat in place, and the countdown keeps running until it completes.
- **Chest task type** (`/pitbox setTaskType <race> <id> chest`): the classic community-style stop. The driver is ejected from the boat, opens the chest placed inside the box zone, and loots the required items from a **full, shuffled grid** — random pit junk pads the remaining slots (per-player GUI; the real chest is only the trigger, so there's no stock to refill or grief). Taking or Q-dropping every required item completes the stop; walking out of the box cancels it, and driving back in restarts it. The box's `duration` is unused in this mode.
- An adaptive title countdown (`5, 4, 3... Go!`) tracks the box's configured duration, with a rising-pitch pling on completion.
- Only one player may occupy a given box at a time; a box can be scoped to specific player names (e.g. a 2-driver team) or opened to everyone.
- Each racetrack a `requiredPitStops` count (`/race setPitStops <race> <amount>`, defaults to 1). Racers who cross the finish line short of that count are automatically **disqualified**, with an immediate broadcast notice and a `DSQ` marker at the bottom of the final standings — below all qualified finishers, in original crossing order.

**Colors:** a preset `PitBoxColor` enum (not free-form hex), including real F1 team livery gradients — McLaren, Mercedes, Red Bull, Ferrari, Williams, VCARB, Aston Martin, Haas, Alpine, Audi, Cadillac — plus solid basics and a few extra gradients (the plugin's own brand gradient, fire, rainbow). Colors render via MiniMessage and apply to both the "BOX BOX" call and the countdown title. Editable post-creation with `/pitbox setColor <race> <id> <color>`.

> Pit box task type is stored as an enum (`TIMED` for now) rather than hardcoded, so future task types can be added without a config migration.

### 🌍 Multi-Language (i18n)

Two built-in languages: `en_US` (default), `fr_FR`. Set in `config.yml`:
```yaml
language: fr_FR
```
Messages use **MiniMessage** (gradients, hex, formatting) + `%placeholder%` substitution. All user-facing text is externalized — add new languages by dropping a `<lang>.yml` in `plugins/IceBoatRacing/lang/`.

---

## 📦 Requirements

| Requirement   | Version                   |
|---------------|---------------------------|
| **Minecraft** | 1.21.4+                   |
| **Paper**     | 1.21.4-R0.1+ (API)        |
| **Java**      | 21 (toolchain enforced)   |

> The plugin uses Paper's Brigadier command API, Adventure text components, and modern NMS mappings — **Paper is required**; Spigot/CraftBukkit/Purpur/Folia untested. `1.21.4` is also the version Paper hard-forked from its former Spigot upstream, making it effectively the floor for the current Paper API — and the version `registerCommand()`, used throughout this plugin's command classes, was introduced in. Built and tested on Java 21 with no errors at this floor.

---

## 📥 Installation

1. **Download** the latest `.jar` from [Modrinth](https://modrinth.com/plugin/ibr) or [CurseForge](https://www.curseforge.com/minecraft/bukkit-plugins/ice-boat-racing), or build from source ([see below](#-building-from-source)).
2. Place `IceBoatRacing-<version>.jar` in your server's `plugins/` folder.
3. **Restart** the server (or `/ibr reload` if hot-reloading).
4. First run generates:
   - `plugins/IceBoatRacing/config.yml`
   - `plugins/IceBoatRacing/lang/en_US.yml`
   - `plugins/IceBoatRacing/lang/fr_FR.yml`
   - `plugins/IceBoatRacing/races/` (directory for race YAMLs)

---

## 🎮 Commands

All commands require the corresponding permission (see [Permissions](#-permissions)). Tab completion works for subcommands, race names, player names, and numeric IDs.

### `/race` — Race Management

| Subcommand                          | Description                                                           | Permission                     |
|-------------------------------------|-----------------------------------------------------------------------|--------------------------------|
| `/race create`                      | Start interactive race creation wizard (player only)                  | `iceboatracing.race.create`    |
| `/race prepare [name] [seconds]`    | Open (or close) the registration intermission and broadcast a clickable Join button. Nobody is teleported until the race starts. | `iceboatracing.command.race`   |
| `/race join [name]`                 | Join an open race. Requires a car registered for that race.           | `iceboatracing.command.race.join` |
| `/race leave [name]`                | Leave the race you joined; puts you back where you joined from        | `iceboatracing.command.race.join` |
| `/race start [name]`                | Begin race with 5-second countdown + lights                           | `iceboatracing.command.race`   |
| `/race end [name]`                  | End race, broadcast final standings                                   | `iceboatracing.command.race`   |
| `/race delete [name]`               | Permanently delete race and its YAML                                  | `iceboatracing.race.create`    |
| `/race setLapCount [name] <amount>` | View or change lap count                                              | `iceboatracing.command.race`   |
| `/race setPitStops [name] <amount>` | View or change required pit-stop count                                | `iceboatracing.command.race`   |
| `/race info`                        | Plugin info (author, purpose)                                         | `iceboatracing.command.plugin` |

> If only one race exists, `[name]` is optional.

#### Registration intermission

`/race prepare` no longer drags every car owner onto the track. It opens a timed
registration window and broadcasts an announcement with a clickable **Join** button:

1. An admin runs `/race prepare <race> [seconds]`.
2. The announcement (and reminders as the clock runs down) appear in chat, each with a
   clickable `[ Join race ]` button.
3. Players click it — or run `/race join <race>` — to add themselves. **Nothing happens to
   them yet**: no teleport, no gamemode change, no boat.
4. When the window closes, everyone who joined is placed in their boat and the countdown
   begins. Players who own a car but never joined are left completely alone.

Run `/race prepare` again (or `/race end`) to close the window early. Behaviour is
controlled by the `race.intermission.*` block in `config.yml`.

### `/checkpoint` — Checkpoint & Track Management

| Subcommand                              | Description                                             | Permission                         |
|-----------------------------------------|---------------------------------------------------------|------------------------------------|
| `/checkpoint create <race> [id]`        | Save BOX checkpoint from shovel selection (pos1/pos2). With `[id]`, insert at that position and shift the rest up. | `iceboatracing.command.checkpoint` |
| `/checkpoint create [sector\|finish] <race> [id]` | Save a SECTOR or START_FINISH checkpoint, optionally at a specific ID | `iceboatracing.command.checkpoint` |
| `/checkpoint override [sector\|finish] <race> <id>` | Replace the checkpoint at `id` in place, keeping its slot in the lap order | `iceboatracing.command.checkpoint` |
| `/checkpoint renumber <race>`           | Rewrite IDs as a contiguous `1..N` range, closing gaps  | `iceboatracing.command.checkpoint` |
| `/checkpoint remove <race> <id>`        | Remove checkpoint by ID (or nearest to player with no args). Leaves no gap behind. | `iceboatracing.command.checkpoint` |
| `/checkpoint view <race>`               | Toggle the particle outline of every checkpoint in that race. Only you see it. Also switches directly if you were viewing another race. | `iceboatracing.command.checkpoint` |
| `/checkpoint list [page]`               | Paginated list of all checkpoints (clickable remove/TP) | `iceboatracing.command.checkpoint` |
| `/checkpoint count`                     | Total checkpoint count across all races                 | `iceboatracing.command.checkpoint` |
| `/checkpoint resetData <race> <player>` | Clear a player's race data (laps, times, sectors)       | `iceboatracing.command.checkpoint` |
| `/checkpoint getID <race>`              | Find nearest checkpoint ID to player                    | `iceboatracing.command.checkpoint` |
| `/checkpoint clearAll <race>`           | **Destructive** — double-run within 10s to confirm      | `iceboatracing.command.checkpoint` |
| `/checkpoint addAlternate <race> <id>`  | Add alternate route to checkpoint (shovel selection)    | `iceboatracing.command.checkpoint` |

#### Checkpoint IDs are the lap order

A checkpoint's numeric ID **is** its position in the lap: racers must cross them in
ascending order, and ID `1` must be the `START_FINISH` line. Consequences:

- IDs must be **contiguous**. A gap makes the race impossible to finish, because the next
  gate is looked up by `index + 1`. `/checkpoint remove` closes the gap automatically, and
  `/checkpoint renumber` fixes one that already exists.
- To insert a gate mid-track, pass the target ID to `create` — the existing checkpoints at
  and after it shift up by one instead of being overwritten:
  ```
  /checkpoint create Monaco 7      # new gate becomes #7, old #7 becomes #8, etc.
  ```
- To move a gate's geometry without disturbing the order, use `override`:
  ```
  /checkpoint override Monaco 7
  ```
- ID surgery is refused while a race is preparing, starting or running, so a racer's
  target can't shift under them mid-lap.
- A race can only have **one** `START_FINISH` checkpoint. A lap both begins and ends by
  crossing it, so a second one would be a gate no racer can ever legitimately cross. The
  first checkpoint of an empty race is always created as `start`/`finish`, whatever subcommand
  form you used.

#### What the outline shows

`/checkpoint view` and the AutoTrace preview both draw through one renderer, so they look
the same and both refresh the moment a checkpoint changes rather than on a timer.

- **PLANE** (auto-traced) — the four edges of the crossing face.
- **BOX** (hand-placed) — a full wireframe of all twelve edges, i.e. the actual trigger volume
  your two shovel corners enclosed. This is what makes a placement verifiable: you can see
  which blocks the gate really covers.
- **Alternates** — a white `CLOUD` rectangle, so a bypass gate is distinguishable from the
  primary one.
- Particles are `FLAME` for start/finish, `HAPPY_VILLAGER` for sectors, `END_ROD` otherwise.

Outlines are sent **only to you**. Creating, overriding or renumbering a checkpoint turns the
preview on for that race if it wasn't already, so you never have to run `view` first. The task
stops on its own when you disconnect, when the race is deleted or its world unloads, and on
`/iceboatracing reload`.

**BOX geometry is inclusive of the blocks you clicked.** Selected blocks are stored as a
volume, not as two corner points, so a gate built on blocks `x=0..5` triggers anywhere in
`[0, 6)` — including the far half of the last block, which older versions missed. `/checkpoint
list` prints and teleports you to the center of that volume.

#### AutoTrace Subcommands

| Subcommand                                                                             | Description                                                      |
|----------------------------------------------------------------------------------------|------------------------------------------------------------------|
| `/checkpoint autotrace start <race> [spacing] [halfWidth] [halfHeight] [loop\|noloop]` | Begin recording; shows interactive config panel                  |
| `/checkpoint autotrace configure <param> <value>`                                      | Adjust spacing/width/height/loop before confirming               |
| `/checkpoint autotrace confirm`                                                        | Start recording with current panel settings                      |
| `/checkpoint autotrace stop`                                                           | Stop recording, generate preview                                 |
| `/checkpoint autotrace preview`                                                        | Toggle particle preview of generated checkpoints                 |
| `/checkpoint autotrace sector`                                                         | Add sector gate at nearest preview checkpoint (shovel selection) |
| `/checkpoint autotrace resize`                                                         | Resize nearest preview checkpoint (click two blocks for width)   |
| `/checkpoint autotrace delete`                                                         | Remove nearest preview checkpoint                                |
| `/checkpoint autotrace accept`                                                         | Commit preview to race YAML                                      |
| `/checkpoint autotrace cancel`                                                         | Discard session                                                  |

### `/pitbox` — Pit Stop Management

| Subcommand                                  | Description                                                                        | Permission                     |
|---------------------------------------------|------------------------------------------------------------------------------------|--------------------------------|
| `/pitbox create <race>`                     | Interactive pit box creation wizard (name → location → duration → allowed → color) | `iceboatracing.command.pitbox` |
| `/pitbox list <race>`                       | List all pit boxes (clickable edit-allowed/color/remove/TP buttons)                | `iceboatracing.command.pitbox` |
| `/pitbox remove <race> <id>`                | Remove a pit box                                                                   | `iceboatracing.command.pitbox` |
| `/pitbox setAllowed <race> <id> <names\|*>` | Change which players may use a pit box                                             | `iceboatracing.command.pitbox` |
| `/pitbox setColor <race> <id> <color>`      | Change a pit box's color/team livery                                               | `iceboatracing.command.pitbox` |
| `/pitbox setTaskType <race> <id> <timed\|chest>` | Switch between a timed hold and a chest-loot stop (see below)                 | `iceboatracing.command.pitbox` |
| `/pitbox setLoot <race> <id> <items> [material]` | Items a chest stop requires (default 5× Snowball)                            | `iceboatracing.command.pitbox` |

### `/car` — Car/Boat Management

| Subcommand                              | Description                                                           | Permission                  |
|-----------------------------------------|-----------------------------------------------------------------------|-----------------------------|
| `/car create`                           | Interactive car registration (spawn block → owner → boat item → race) | `iceboatracing.command.car` |
| `/car list [page]`                      | Paginated list of all cars (clickable remove/TP/changeOwner)          | `iceboatracing.command.car` |
| `/car remove <race> <id>`               | Delete a car (alias: `/car delete`)                                   | `iceboatracing.command.car` |
| `/car changeOwner <race> <id> <player>` | Reassign car ownership                                                | `iceboatracing.command.car` |
| `/car selectRace <race>`                | Select race for car creation (used by wizard)                         | `iceboatracing.command.car` |

### `/ibr` or `/iceboatracing` — Plugin Control

| Subcommand    | Description                                 | Permission                     |
|---------------|---------------------------------------------|--------------------------------|
| `/ibr reload` | Reload config, languages, races, scoreboard | `iceboatracing.command.plugin` |
| `/ibr info`   | Show plugin info (author, purpose)          | `iceboatracing.command.plugin` |

---

## 🔐 Permissions

| Permission                         | Description                        | Default |
|------------------------------------|------------------------------------|---------|
| `iceboatracing.*`                  | All plugin permissions             | `op`    |
| `iceboatracing.command.*`          | All command permissions            | `op`    |
| `iceboatracing.command.race`       | `/race` commands                   | `false` |
| `iceboatracing.command.car`        | `/car` commands                    | `false` |
| `iceboatracing.command.checkpoint` | `/checkpoint` commands             | `false` |
| `iceboatracing.command.pitbox`     | `/pitbox` commands                 | `false` |
| `iceboatracing.command.plugin`     | `/ibr` commands                    | `false` |
| `iceboatracing.race.*`             | Full race management               | `op`    |
| `iceboatracing.race.create`        | Create/delete races, set lap count | `false` |
| `iceboatracing.race.checkpoints`   | Manage checkpoints                 | `false` |
| `iceboatracing.race.cars`          | Manage cars                        | `false` |

> **Recommendation:** Give operators `iceboatracing.*`; for staff roles, grant specific `iceboatracing.race.*` or `iceboatracing.command.*` children.

---

## ⚙️ Configuration

### `config.yml`

```yaml
# Language file to load from lang/ (without .yml)
language: en_US

race:
  # Allow players to rejoin mid-race after logout
  allowRejoin: true

  # Put players back where they joined the race once they leave or finish.
  # Not persisted: after a restart, the world spawn is used instead.
  teleportBackAfterRace: true

  # Registration intermission: /race prepare announces a clickable Join
  # button and only players who click it take part.
  intermission:
    seconds: 120          # how long registration stays open
    autoStart: true       # start when the window closes (false = admin starts manually)
    minRacers: 1          # refuse to start below this many joined players
    reminderSeconds: [60, 30, 10]   # when to repeat the Join button

  # Remove player's car on logout
  removeCarWhenLoggingOut: false

  # Race starting lights (stained-glass countdown)
  lights:
    enabled: false
    from:
      x: 123456789
      y: 123456789
      z: 123456789
    to:
      x: 123456789
      y: 123456789
      z: 123456789
```

**Notes:**
- `lights.from`/`to` default to `123456789` — if unchanged, lights are **disabled** regardless of `enabled: true`.
- The plugin **never changes a player's gamemode**. Racers keep whatever mode they arrived in, which is what makes this usable on a survival server. (The old `racingGameMode` / `enforceRacingGameMode` / `spectateOnFinish` keys are gone; leaving them in an existing config file is harmless.)
- **Pit stops are not configured in `config.yml`** — they're per-race (`requiredPitStops`, set via `/race setPitStops`) and per-box (location, duration, allowed players, color, set via `/pitbox create`/`setAllowed`/`setColor`), living in each race's own YAML like checkpoints and cars.

### `paper-plugin.yml` (embedded in JAR)

Defines plugin metadata, permissions tree, and load order (`POSTWORLD`). Not user-editable.

### Language Files (`lang/*.yml`)

All messages externalized with MiniMessage formatting. Key sections:
- `prefix` — global message prefix (gradient)
- `noPrefix` — scoreboard title, join/quit messages
- `checkpoint.*` — all checkpoint/AutoTrace messages
- `race.*` — race lifecycle, lap/sector/finish broadcasts, final standings, pit-stop count
- `pitbox.*` — pit box creation, list, countdown, disqualification-adjacent messages
- `car.*` — car creation, errors
- `error.*` — generic errors

Add a new language by copying `en_US.yml` → `<lang>.yml`, translating values, and setting `language: <lang>` in `config.yml`.
Don't edit the original language files as your edits will be overwritten on restart to guarantee that there are no missing messages when you update.

---

## 🛠 How It Works

### Data Flow

```
Race Creation (RaceCreator)
    │
    ├── Step 1-2: Name + Laps → Race object + YAML
    ├── Step 3-4: AutoTrace (CheckpointCommand + AutoTraceManager)
    │       ├── Record player positions (2-tick interval)
    │       ├── On loop close / stop → simplify → resample → recenter on ice
    │       ├── Build PLANE checkpoints (oriented rectangles)
    │       ├── Particle preview (toggleable)
    │       └── Accept → batch write to race YAML
    ├── Step 5-6: Car Creation (CarCreator + CarManager)
    │       ├── Break block → spawn location
    │       ├── Owner name → UUID
    │       ├── Held boat item → type + custom name
    │       └── Save to race YAML (cars.<id>.*)
    └── Done → Race ready

Pit Box Creation (PitBoxCreator) — separate flow, run any time after the race exists
    │
    ├── Step 1: Name
    ├── Step 2: Location (wooden shovel wand, reuses CheckpointCommand's pos1/pos2)
    ├── Step 3: Duration (seconds)
    ├── Step 4: Allowed players (names or "*")
    ├── Step 5: Color (preset PitBoxColor)
    └── Save → PitBoxManager.savePitBox() → race YAML (pitboxes.<id>.*)
```

### Race Execution (`RaceManager` + `RaceListener`)

1. **`/race prepare`** → `openRegistration()`
   - Sets `preparingRace = true` and broadcasts a clickable Join announcement
   - Runs a countdown with reminders; nobody is teleported or gamemode-switched
   - `/race join` → `joinRace()`: creates a `Racer`, captures their return location, requires a
     registered car. Still no teleport, no boat, no gamemode change.

2. **`/race start`** → `startRace()` (automatic when the intermission ends)
   - Refuses to start with an empty roster
   - For each *joined* racer: `CarManager.spawnCar()` → spawns invulnerable boat, sets custom
     name, rotates, adds passenger
   - 6-tick countdown (5→4→3→2→1→GO!) with titles, sounds, optional lights
   - Sets `hasRaceStarted = true`

3. **`RaceListener.onMove()`** (every `PlayerMoveEvent` in a boat)
   - Validates: in vehicle, is boat, has race data, racer still active
   - **Segment-ray intersection** against `nextCheckpoint` (and `next+1` for skip tolerance)
   - `Checkpoint.crosses(from, to)` handles both BOX (slab intersection) and PLANE (ray-plane) + alternates
   - On `START_FINISH`:
      - Lap 0 → set `startTime`
      - Lap < total → `onCompleteLap()` (broadcast, title, best-lap check, scoreboard update)
      - Lap == total → `onFinishRace()` (ranking, gaps, stats, teleport back, disqualification check)
   - On `SECTOR` → record sector split, broadcast
   - **Pit box check** runs right after the checkpoint loop, reusing the same `Racer` lookup — no second listener re-iterating racing boats:
      - Skip if `pitStopsCompleted >= requiredPitStops` (already done) or the player is mid-stop (`pittingBox != null`)
      - Otherwise, for each unoccupied `PitBox` the player is allowed to use and physically inside: start a session — occupy the box, freeze velocity every tick via a repeating task, run the adaptive countdown title, and on completion increment `pitStopsCompleted` and release the box

4. **`onFinishRace()`**
   - Computes `disqualified = pitStopsCompleted < race.getRequiredPitStops()` at the moment of finish-line crossing
   - Broadcasts a disqualification notice immediately if applicable
   - Ranking/gap/stats messages otherwise unchanged

5. **`/race end`** or **last finisher** → `endRace()`
   - Broadcasts `race.end`
   - Destroys cars, clears scoreboard, resets race state
   - Sends full standings (`Race.sendRanking()`), which walks finish order by crossing index, lists all qualified finishers first, then disqualified racers at the bottom under a `DSQ` marker

### Persistence

Each race = one YAML file: `plugins/IceBoatRacing/races/<name>.yml`

```yaml
name: "Monaco"
world: "world"
lapCount: 3
requiredPitStops: 2
checkpoints:
  1:
    shape: PLANE
    world: "world"
    center: "100.5,64.0,200.3"
    normal: "0.0,0.0,-1.0"
    halfWidth: 4.5
    halfHeight: 3.0
    type: START_FINISH
  2:
    shape: PLANE
    #...
pitboxes:
  1:
    name: "Team Red"
    world: "world"
    min: "95,64,180"
    max: "98,66,183"
    taskType: TIMED
    duration: 5
    allowed:
      - "*"
    color: FERRARI
cars:
  1:
    world: "world"
    startingLocation: "100,65,200"
    owner: "123e4567-e89b-12d3-a456-426614174000"
    boatMaterial: "OAK_BOAT"
    boatCustomName: "Car #1"
```

Loaded on startup via `RaceManager.loadAllRaces()` → `CheckpointManager.loadRaceCheckpoints()` + `CarManager.loadCars()` + `PitBoxManager.loadRacePitBoxes()`.
Or reloaded from file by using `/ibr reload`.

---

## 💡 Examples & Usage

### Quick Start: Create a Race in 2 Minutes

```bash
# 1. Create race (wizard)
/race create
# → Enter name: "Monaco"
# → Enter laps: "3"
# → "yes" to define track
# → Stand on start line, type anything in chat
# → Drive one full lap
# → /checkpoint autotrace stop
# → /checkpoint autotrace preview  (verify)
# → /checkpoint autotrace accept
# → "yes" to define cars
# → For each car: break spawn block → type owner name → hold boat → "save"
# → Type "save" when done

# 2. Open registration, then start
/race prepare Monaco 300    # 5-minute registration window, announces a Join button
# players click [ Join race ] in chat, or run /race join Monaco
# the race starts automatically when the window closes
# (or run /race start Monaco yourself at any point)
```

### Add Sector Gates After AutoTrace

```bash
/checkpoint autotrace start Monaco
# (preview already exists from previous accept)
/checkpoint autotrace preview
# Walk to sector location, shovel-click two blocks for gate width
/checkpoint autotrace sector
# Repeat for each sector
/checkpoint autotrace accept
```

### Add Alternate Route (ex: Pit Lane)

```bash
# Shovel-select two corners of pit lane gate
/checkpoint addAlternate Monaco 1
```

### Add Mandatory Pit Stops

```bash
# Require 2 pit stops to finish
/race setPitStops Monaco 2

# Create a pit box (wizard)
/pitbox create Monaco
# → Enter name: "Team Red"
# → Shovel-select 2 corners of the pit box zone, type "confirmer"
# → Enter duration: "5"
# → Enter allowed players: "*"
# → Enter color: "FERRARI"

# Edit later without redoing the wizard
/pitbox setAllowed Monaco 1 Alex Steve
/pitbox setColor Monaco 1 MCLAREN
```

### Change Language to French

```yaml
# config.yml
language: fr_FR
```
Then `/ibr reload`.

### Customize Race Lights

```yaml
# config.yml
race:
  lights:
    enabled: true
    from:
      x: 100
      y: 64
      z: 200
    to:
      x: 105
      y: 66
      z: 205
```
Build a 3D frame at those coordinates — plugin will fill it with stained-glass on countdown.

---

## ❓ FAQ

<details>
<summary><strong>Why does the plugin require Paper (not Spigot/Purpur/Folia)?</strong></summary>
It uses Paper-specific APIs: Brigadier command registration (`io.papermc.paper.command.brigadier`), Adventure components (`net.kyori.adventure.*`), and modern NMS mappings. These are not available on Spigot forks.
</details>

<details>
<summary><strong>Can I use this on Folia (multithreaded Paper)?</strong></summary>
Not tested. The plugin uses main-thread-only Bukkit APIs (entity spawning, particle effects, scoreboard) and assumes single-threaded execution. Folia support would require significant refactoring.
</details>

<details>
<summary><strong>My boats face the wrong direction on start. How do I fix it?</strong></summary>
Set `startRotation` in the race's config file (degrees: 0=South, 90=West, 180=North, 270=East). Reload with `/ibr reload`.
</details>

<details>
<summary><strong>AutoTrace generated checkpoints in the wrong order / missed a turn.</strong></summary>
- Ensure you drive a **clean, continuous lap** without backtracking.
- Increase `spacing` (e.g., 5–8 blocks) for long straights; decrease for tight corners.
- Use `/checkpoint autotrace resize` / `delete` to fix individual gates before `accept`.
- For complex layouts, consider placing a few manual PLANE checkpoints via YAML.
</details>

<details>
<summary><strong>I can't see the checkpoint particles.</strong></summary>
Run <code>/checkpoint view &lt;race&gt;</code>. It only draws for the player who ran the command —
other players nearby see nothing by design. If you still see nothing, check you're within 64 blocks
of the gates and in the race's world. Note that a race with zero checkpoints closes the preview
immediately, so build at least one first.
</details>

<details>
<summary><strong>Checkpoints aren't registering for a player.</strong></summary>
Check the player actually joined the race — only players on the roster are processed. It is
normal for them to have no boat before the race starts: joining does not move them. Also
confirm the track's IDs are contiguous (<code>/checkpoint list</code>, or run
<code>/checkpoint renumber &lt;race&gt;</code>); a gap silently stops all progress because the
next gate is looked up by <code>index + 1</code>.
</details>

<details>
<summary><strong>How do I run a race on a survival server without disrupting everyone?</strong></summary>
Use the registration intermission: <code>/race prepare &lt;race&gt;</code> only announces a
clickable Join button. Players opt in, and the plugin never changes anyone's gamemode — racers
keep the mode they arrived in, and non-racers are never teleported. Each racer is put back
where they joined when they leave or finish (<code>race.teleportBackAfterRace</code>), and the
live sidebar is per-player, so it never appears for the rest of the server.
</details>

<details>
<summary><strong>What happens if a player skips a required pit stop?</strong></summary>
They're automatically disqualified the moment they cross the finish line with fewer completed stops than the race's <code>requiredPitStops</code> value, get an immediate broadcast notice, and appear separately at the bottom of the final standings under a <code>DSQ</code> marker. This is checked at finish-line crossing, not enforced by blocking the finish line itself — a player can still complete a lap without having pitted, they just won't be counted as a qualified finisher.
</details>

<details>
<summary><strong>Can I lock a pit box to a specific team?</strong></summary>
Yes — <code>/pitbox setAllowed &lt;race&gt; &lt;id&gt; &lt;names...&gt;</code> restricts a box to specific player names (e.g. both drivers on a team), or use <code>*</code> for anyone racing. Only one player can occupy a given box at a time regardless of who's allowed.
</details>

<details>
<summary><strong>How do I back up/migrate races?</strong></summary>
Copy the `plugins/IceBoatRacing/races/` folder (each `.yml` is a self-contained race, including its pit boxes). To migrate to another server, copy the folder and the `config.yml` + `lang/` files.
</details>

<details>
<summary><strong>Can I have multiple races running simultaneously?</strong></summary>
Yes — `RaceManager.activeRaces` supports multiple concurrent races. Each racer gets their own per-player sidebar, so two races can run at once without their standings colliding, and non-racers keep whatever scoreboard they had. `/race start/prepare/end` accept a race name to target a specific one.
</details>

<details>
<summary><strong>What's the difference between BOX and PLANE checkpoints?</strong></summary>
**BOX** = axis-aligned cuboid (two corners). Simple but forces 90° angles. **PLANE** = oriented rectangle (center + normal + dimensions). Supports any angle, curved tracks, elevation changes. AutoTrace only produces PLANE. PLANE uses ray-plane intersection for precise crossing detection.
</details>

<details>
<summary><strong>Does the plugin support chest boats / bamboo rafts?</strong></summary>
Yes — all vanilla boat types including chest variants (`*_CHEST_BOAT`, `BAMBOO_CHEST_RAFT`) and `PALE_OAK_BOAT` (1.21.4+). Just hold the item during `/car create` step 3.
</details>

---

## 🧰 Developer API

IceBoatRacing does **not** currently expose a public Java API for other plugins. However, the following internal classes are stable enough for hooking (use at your own risk):

| Class               | Purpose                                                                                                                    |
|---------------------|----------------------------------------------------------------------------------------------------------------------------|
| `RaceManager`       | `getRace(String)`, `getRaces()`, `startRace(Race)`, `endRace(Race)`, `togglePrepareRace()`                                 |
| `CheckpointManager` | `saveCheckpoint()`, `saveTracedCheckpoints()`, `loadRaceCheckpoints()`, `toggleViewCheckpoints()`                          |
| `PitBoxManager`     | `savePitBox()`, `loadRacePitBoxes()`, `startSession()`, `setAllowed()`, `setColor()`                                       |
| `CarManager`        | `saveCar()`, `spawnCar()`, `changeOwner()`, `getAll()`                                                                     |
| `AutoTraceManager`  | `start()`, `stop()`, `generatePreview()`, `accept()`, `togglePreview()`                                                    |
| `Race`              | `getCheckpoints()`, `getCars()`, `getPitBoxes()`, `getLapCount()`, `getRequiredPitStops()`, `racers` (Map<UUID, RaceData>) |
| `Racer`          | `lapTimes`, `sectorsTimes`, `pitStopsCompleted`, `disqualified`, `bestLapTime()`, `meanLapTime()`, `getRaceTime()`         |
| `Checkpoint`        | `crosses(Location, Location)`, `contains(Location)`, `getAlternates()`                                                     |
| `PitBox`            | `contains(Location)`, `isAllowed(String)`, `isOccupied()`, `getColor()`                                                    |
| `Messages`          | `getMessage(key, args...)`, `formatArguments(...)`                                                                         |

> **Stability:** No semantic versioning guarantee for internal classes. If you build an integration, consider forking or contacting the maintainer for a proper API module.

### Events You Can Listen To

All standard Bukkit events work. The plugin fires no custom events currently.

---

## 🏗 Building from Source

**Requirements:** JDK 21, Gradle (wrapper included)

```bash
# Clone
git clone https://github.com/mattmunichYT/IceBoatRacing.git
cd IceBoatRacing

# Build (outputs to build/libs/)
./gradlew build

# Run tests (currently none)
./gradlew test

# Clean
./gradlew clean

# Shadow JAR (if dependencies added)
./gradlew shadowJar
```

**Project Structure:**
```
src/main/java/fr/mattmunich/iceBoatRacing/
├── Main.java                 # Plugin entry point, lifecycle, config, commands, listeners
├── Messages.java             # i18n, MiniMessage, placeholders
├── IBRCommand.java           # /ibr command
├── race/
│   ├── Race.java             # Race model, YAML persistence, rankings, DSQ-aware standings
│   ├── RaceManager.java      # Race loading/saving/starting/ending
│   ├── RaceCommand.java      # /race command (incl. setPitStops)
│   ├── RaceListener.java     # PlayerMoveEvent → checkpoint crossing + pit box detection
│   ├── RaceCreator.java      # Interactive wizard (AsyncChatEvent)
│   └── RaceData.java         # Per-player runtime data (laps, sectors, times, pit stops, DSQ)
├── checkpoint/
│   ├── Checkpoint.java       # BOX/PLANE geometry, alternates, crossing logic
│   ├── CheckpointManager.java# Checkpoint persistence
│   ├── CheckpointRenderer.java # Particle outlines, shape-aware, per-viewer
│   ├── CheckpointPreview.java# /checkpoint view sessions + lifecycle
│   ├── CheckpointCommand.java# /checkpoint + AutoTrace UI
│   ├── CheckpointGeometry.java# RDP simplify, resample, recenter, plane build
│   └── autotrace/
│       ├── AutoTraceManager.java   # Recording, preview, editing, commit
│       ├── AutoTraceSession.java   # Per-player session state
│       └── PendingAutoTraceConfig.java # Config panel state
├── pitbox/
│   ├── PitBox.java            # Pit box model (AABB, task type, duration, allowed, color, occupancy)
│   ├── PitBoxManager.java     # Persistence + active session handling (velocity freeze, countdown)
│   ├── PitBoxColor.java       # Preset solid/gradient MiniMessage color enum
│   ├── PitBoxCreator.java     # Interactive wizard (AsyncChatEvent)
│   ├── PitBoxCommand.java     # /pitbox command
│   └── PendingPitBoxConfig.java # Wizard session state
├── cars/
│   ├── Car.java               # Boat entity wrapper + metadata
│   ├── CarManager.java        # Car persistence + spawning
│   ├── CarCommand.java        # /car command
│   ├── CarCreator.java        # Interactive car registration
│   └── CarListener.java       # Countdown exit lock, grid/pit freeze, mid-race exit replaces the boat
└── listeners/
    ├── Connection.java       # Player join/quit during races
    └── WorldLoad.java        # (Unused, reserved)
```

---

## 🤝 Contributing

Contributions welcome! Please:

1. **Fork** the repository
2. **Create a branch** — `feature/amazing-thing` or `fix/issue-description`
3. **Follow the code style** — existing formatting, MiniMessage for messages, Brigadier for commands
4. **Test manually** — no automated test suite yet; spin up a Paper server
5. **Submit a PR** with a clear description

**Ideas from TODO.md:**
- Fix logout/rejoin mid-race
- Pit box task variety (currently `TIMED` only — e.g. skill-based stops)
- Enforce pit stops at the finish line itself rather than DSQ-ing after crossing
- Easier alternate route creation
- Convert legacy config.yml races to per-race YAML
- More tests

---

## 🐛 Reporting Issues

Use the **GitHub Issues** tab. Please include:

- Paper version (`/version` output)
- Java version (`java -version`)
- Plugin version (`/ibr info`)
- Steps to reproduce
- Relevant `config.yml` snippets
- Console errors (if any)

---

## 📄 License

**MIT License** — see [LICENSE](LICENSE) for full text.

```
Copyright (c) 2026 mattmunich

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction...
```

---

## 🙏 Credits

- **Author:** [mattmunichYT](https://github.com/mattmunichYT)
- **Built for:** **Grands Prix** by **Mini Jeux Entre Potes**
- **Dependencies:** [Paper API](https://papermc.io) (compile-only), [Adventure](https://github.com/KyoriPowered/Adventure) (shaded via Paper), [MiniMessage](https://github.com/KyoriPowered/Adventure/tree/master/text/minimessage)
- **Inspiration:** Mario Kart-style lap/timing system (original implementation); real F1 team liveries for pit box colors
- **Special thanks:** PaperMC team for the excellent API

---

<div style="text-align: center;">

**Made with ❄️ for the Minecraft racing community**

[⬆ Back to top](#-iceboatracing)

</div>
