# Hide and Seek - Fabric mod for Minecraft Java 26.3 (server-side) - v1.1.0

Two modes: **Normal** (caught hiders go to a prison) and **Sabotage** (caught hiders become seekers).
**No game ever starts by itself: an administrator must start every game.**
The mod is declared `"environment": "server"`: players need nothing installed; vanilla 26.3 clients can join.

## Requirements
| | |
|---|---|
| Minecraft | Java Edition 26.3 |
| Java | 25 |
| Fabric Loader | 0.19.5 or newer |
| Fabric API | 0.163.0+26.3 (newer 26.3 builds should also work) |

## Build
Requires a JDK 25 and internet. The Gradle wrapper is included, so Gradle need not be installed.
* Windows: `gradlew.bat build` (or double-click `build.bat`)  * Linux/macOS: `./gradlew build` (or `./build.sh`)

Output: `build/libs/hideandseek-1.1.0.jar` (not the `-sources` jar).

## Install
1. Install Fabric for 26.3 on your server (https://fabricmc.net/use/server/).
2. Put `fabric-api-0.163.0+26.3.jar` and `hideandseek-1.1.0.jar` in `mods/`.
3. Start the server. Settings are saved in `config/hideandseek.json` (changed only through commands).

## Commands (operators / permission level 2)
| Command | Effect |
|---|---|
| `/hideandseek setprison` | Save your position as the Normal Mode prison (required for Normal). |
| `/hideandseek setlobby` | Save your position as the lobby (participants are teleported here when a game starts). |
| `/hideandseek start normal [seekers]` | Start Normal Mode. |
| `/hideandseek start sabotage [seekers]` | Start Sabotage Mode. |
| `/hideandseek seeker <player>` | Idle: pre-assign as seeker for the next game (either mode). In a game: make them a seeker. |
| `/hideandseek hider <player>` | Idle: force as hider. In a game: make them a hider / free a prisoner. |
| `/hideandseek random` | Idle: pre-assign one random seeker. In a game: turn a random free hider into a seeker. |
| `/hideandseek setseekers <n>` | Default seeker count for both modes (default 1). |
| `/hideandseek settimelimit <minutes>` | Game timer; `0` disables it. |
| `/hideandseek setpreptime <seconds>` | Seekers' frozen preparation time (default 60; `0` = none). |
| `/hideandseek stop` | Stop immediately and clean up. |
| `/hideandseek status` / `help` | Information. |

Seekers = players pre-assigned with `seeker`/`random`, topped up with random players to the requested/default count.
Pre-assigned selections apply to the next start only.

## How a game runs
1. **Start (v1.1.0):** all participants - and only them - are teleported to the **lobby**, and the game rule `pvp` is set to
   `true`. (If no lobby is set, nobody is teleported and the start message warns you.)
2. **Preparation** (`setpreptime`, default 60 s; 0 = skipped): roles/teams assigned, everyone in Adventure mode, equipment
   given. Seekers are held in place (any horizontal movement is reverted every tick); hiders move freely. The action bar
   counts down. Then all seekers are released at once and the game timer (`settimelimit`) starts.
3. **Normal Mode:** a hider killed by a seeker dies normally and, on respawning, is sent to the prison and becomes a
   prisoner (immune, cannot hurt anyone). **Nothing keeps prisoners in the prison any more** (v1.1.0): they can walk out;
   build your prison with barrier blocks. Seekers win when no free hider remains; hiders win on timeout.
4. **Sabotage Mode:** a hider killed by a seeker dies, respawns and becomes a seeker at once. The game ends permanently
   when every hider is converted (seekers win) or the timer expires (hiders win). No automatic next round.
5. **Respawning during a game (v1.1.0):** any participant who dies and respawns while a game is running is teleported to the
   **lobby** after the respawn has happened (their role is never changed). The one exception is a caught Normal-Mode hider,
   who goes to the prison, because that is the "caught" mechanic. Outside a game, respawning is vanilla.
6. **End of a game (any reason, including `stop`):** `pvp` is set to `false` immediately. For natural endings the result is
   announced and the mod waits exactly **5 seconds** (fixed); `stop` cleans up at once. Cleanup teleports **every
   participant (prisoners included) to the Overworld world spawn**, restores each player's original game mode, removes mod
   equipment/teams/effects, cancels all scheduled tasks, restores the game rules and returns to **IDLE**. Players who are
   dead at that moment are moved to the world spawn when they respawn; players who are offline are moved when they next
   join. The cleanup runs once per game (it cannot run twice).

## Reconnecting (v1.1.0)
Roles, teams, equipment records and prison status are stored per player UUID for the whole game in **both** modes and are
not removed when a player disconnects. Rejoining restores the same role and team (previously Sabotage reset players to
Hider). Players who were never in the game join as hiders. Offline hiders still count as hiders; if a whole team is
offline the round waits, and after 5 minutes it ends with no winner. Role data is discarded when the game ends. Game
state is not saved across a server restart (a game does not resume; leftovers such as Adventure mode and game rules
are repaired automatically at the next start/join).

## Automatic equipment (both modes, every game)
Given once, when roles are set up, to participants only:
* **Everyone:** exactly 1 Ender Pearl.
* **Seekers only:** 1 Bow (Infinity only), 1 normal Arrow, 1 Stone Sword (Sharpness IV only).
* **Arrow glow:** a normal arrow does not make targets glow in vanilla, so the mod applies Glowing for 5 seconds to a hider
  hit by an arrow from a seeker (a melee hit never shortens it).
* A hider converted in Sabotage / promoted by command gets the seeker kit at once and no second pearl; a demoted seeker
  loses the kit and keeps the pearl. Mod items carry a hidden marker so only they are removed (before a game, at the end,
  and for players who return after a game ended); other items are never touched.

## During a game (temporary, restored afterwards)
* Teams: hiders blue, seekers red, name tags hidden. Glowing for exactly 3 s when a seeker hits a hider (refreshed by each
  hit); never for hider-vs-hider.
* Game rules: `pvp=true` (set at start, `false` at end), `keep_inventory=true` and `locator_bar=false` (their original
  values are saved in the config and restored). **These rules are global while a game runs.**
* Hunger stays full for participants. Each participant's original game mode is stored per player and restored at the end.

## Limitations
* Seeker freeze is a position lock; frozen seekers may see slight rubber-banding.
* A caught Normal-Mode hider must press Respawn on the death screen; the move to the prison follows immediately.
* Prisoners are not confined (use barrier blocks).
* Game rule changes are server-wide while a game runs; `pvp` is forced to `false` at every game end.

## Verification status (v1.1.0) - please read
| Item | Status |
|---|---|
| v1.0.0 built with the real Gradle/Loom toolchain and produced a jar | Yes (provided by the project owner; its build output was inspected) |
| v1.1.0 source compiled with `javac` against the **real Minecraft 26.3 classes** (extracted from Loom's cache), with only the Fabric API / Brigadier / Gson / SLF4J libraries stubbed | **Yes - 0 errors** |
| Full `gradlew build` with Loom, Fabric API and the final `hideandseek-1.1.0.jar` | **Not run by the author of this release** (no network/JDK 25 where it was written). Run `gradlew.bat build`. |
| Dedicated-server load test and any in-game behaviour of the v1.1.0 changes | **Not tested** |
