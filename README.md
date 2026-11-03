# Hide and Seek - Fabric mod for Minecraft Java 26.3 (server-side)

Two modes: **Normal** (caught hiders go to a prison) and **Sabotage** (caught hiders become seekers).
**No game ever starts by itself: an administrator must start every game.** `"environment": "server"`: players need nothing installed; vanilla 26.3
clients can join.

## Requirements
| | |
|---|---|
| Minecraft | Java Edition 26.3 |
| Java | 25 (a full JDK to build, a JRE/JDK to run) |
| Fabric Loader | 0.19.5 or newer |
| Fabric API | 0.163.0+26.3 (newer 26.3 builds should also work) |

## Build
Needs JDK 25 and internet; Gradle is downloaded automatically.
* Windows: run `build.bat`   * Linux/macOS: `./build.sh`

The script downloads Gradle 9.7.1 once, generates the full wrapper (`gradlew`, `gradlew.bat`,
`gradle/wrapper/gradle-wrapper.jar`) and runs `build`. Afterwards `gradlew.bat build` also works.
Output: `build/libs/hideandseek-1.0.0.jar` (not the `-sources` jar).

## Install
1. Install Fabric for 26.3 on your server (https://fabricmc.net/use/server/).
2. Put `fabric-api-0.163.0+26.3.jar` and `hideandseek-1.0.0.jar` in `mods/`.
3. Start the server. Settings are saved in `config/hideandseek.json` (changed only through commands).

## Commands (operators / permission level 2)
| Command | Effect |
|---|---|
| `/hideandseek setprison` | Save your position as the Normal Mode prison (required for Normal). |
| `/hideandseek setlobby` | Save your position as the lobby. |
| `/hideandseek start normal [seekers]` | Start Normal Mode. |
| `/hideandseek start sabotage [seekers]` | Start Sabotage Mode. |
| `/hideandseek seeker <player>` | Idle: pre-assign as seeker for the next game (**either mode**). In a game: make them a seeker. |
| `/hideandseek hider <player>` | Idle: force as hider (never picked randomly). In a game: make them a hider / free a prisoner. |
| `/hideandseek random` | Idle: pre-assign one random seeker. In a game: turn a random free hider into a seeker. |
| `/hideandseek setseekers <n>` | Default seeker count for **both** modes (default 1). |
| `/hideandseek settimelimit <minutes>` | Game timer; `0` disables it. |
| `/hideandseek setpreptime <seconds>` | How long seekers stay frozen at the start of a game (both modes). Default **60**; `0` = seekers are released immediately; negative values are rejected. Saved in the config. Independent of the game time limit. |
| `/hideandseek stop` | Stop immediately; cancels all tasks, restores everything. |
| `/hideandseek status` / `help` | Information. |

**Seeker selection (both modes):** seekers = players pre-assigned with `seeker`/`random`, topped up with random players
until the requested count (`start ... <n>`) or the default (`setseekers`) is reached. Pre-assigned players can be set
before or after choosing the mode. They apply to the next start only (consumed when the game starts).

## How a game runs
1. **Preparation (`setpreptime`, default 60 s; 0 = skipped):** roles/teams assigned, everyone in Adventure mode. Seekers are held in place (any horizontal
   movement is reverted every tick; they can still look around). Hiders move freely. The action bar (and titles at 30, 10, 5-1) count down. No game timer exists yet.
2. **Release:** all seekers are released at once; **now** the game timer starts (if `settimelimit` > 0).
   The action bar shows `Time remaining: mm:ss` once per second. One deadline value drives it, so there is never more
   than one timer; it is cleared when the round ends or on `stop`.
3. **Normal Mode:** a hider killed by a seeker dies normally, respawns into the prison (Adventure, immune, cannot hurt,
   pulled back if >8 blocks away). Seekers win when no free hider remains; hiders win on timeout. After the fixed 5-second delay, every prisoner is teleported to the **world spawn** (dead ones when they respawn), other players go to the
   lobby (if set), game modes/teams/rules are restored and the mod is **IDLE** - nothing restarts automatically.
4. **Sabotage Mode:** a hider killed by a seeker dies, respawns normally and becomes a seeker at once. The game ends
   **permanently** when every hider has been converted ("ALL PLAYERS HAVE BEEN CONVERTED! SEEKERS WIN!") or the timer
   expires (surviving hiders win). If fewer than 2 players remain, the game is aborted. There is no automatic next round
   or new preparation phase; an admin must run `/hideandseek start ...` again.

**End of every game (both modes, both end reasons):** announce the result -> wait **exactly 5 seconds** (fixed; not
configurable and unrelated to the preparation time) -> cleanup: restore each player's original game mode and the
game rules, remove teams and equipment, cancel all scheduled tasks, send prisoners to the world spawn and other
participants to the lobby (if set) -> **IDLE**. `stop` skips the wait and cleans up at once.

**No automatic restarts, ever.** Nothing in the mod can start a game: no round loop, no new seeker selection, no new
preparation, no delayed start. Every game (Normal or Sabotage) begins only when an admin runs a `start` command. The only
delayed task after a game ends is the 5-second cleanup, and every delayed task is invalidated when a game stops or starts.

## Automatic equipment (both modes, every game)
Given once, when the round is set up (the start of the preparation phase), to **participants only** (never to
spectators or players outside the game):
* **Everyone (hiders and seekers):** exactly **1 Ender Pearl**.
* **Seekers only:** 1 **Bow** with **Infinity** (no other enchantments), exactly **1 normal Arrow**, and
  1 **Stone Sword** with **Sharpness IV** (no other enchantments). Hiders never receive these.
* **Arrow glow (5 s):** the mod gives one normal arrow and applies a mod-managed Glowing effect for 5 seconds when it hits a hider. The mod removes that effect after 5 seconds; a later arrow hit refreshes the timer. A melee hit does not shorten an active arrow glow.
* **Roles changing:** a hider converted in Sabotage (or promoted with `/hideandseek seeker`) receives the seeker kit
  immediately and **no second pearl**; a seeker demoted with `/hideandseek hider` loses the seeker kit and keeps the
  pearl. Late joiners get the hider pearl. The mod remembers each player's kit, so repeated callbacks never duplicate items.
* **Clean slate:** every item carries a hidden marker. Before a game's kit is given, and again at game end, the mod removes
  only its own marked items (old pearls/kit), so nothing stacks or carries over; unrelated items are never touched.
  Players who were offline at game end are cleaned when they next join.
* Equipment survives death/respawn through the temporary `keep_inventory` rule.
* If Infinity keeps the single arrow depends on vanilla rules for the 26.3 version (untested).
* If a player's inventory is full, they are told that part of the kit could not be given (nothing is dropped).

## Cleanup after every game
Runs whenever a game ends - Sabotage by timer, Sabotage by all hiders converted, Normal by either result, or `stop` -
after the fixed 5-second delay (`stop` runs it at once), always through the same single code path:
1. Glowing removed; all mod equipment removed from participants.
2. Teams and team memberships deleted (this also removes hidden-nametag settings with them).
3. Each player's own original game mode restored; `keep_inventory` / `locator_bar` restored to their original values.
4. Roles, prison state, frozen positions, equipment records, timers and every scheduled task cleared.
5. Prisoners to the world spawn, other participants to the lobby (if set); dead players when they respawn.
6. State set to **IDLE**. Nothing starts again until an admin starts a game.
The mod creates no entities or world objects, so there is nothing of that kind to remove. Unrelated permanent player
data and unrelated server settings are not modified.

During a game (temporary, restored afterwards)
* Teams: hiders blue, seekers red, name tags hidden (team property, so respawns/role changes keep it).
* Glowing for 3 s on a seeker melee hit, or 5 s on a seeker arrow hit (refreshed by each hit); never for hider-vs-hider.
* Game rules `keep_inventory=true` (no drops, no loss, no duplication) and `locator_bar=false`. The original values are
  saved in `config/hideandseek.json` and restored on game end, `stop`, shutdown, or the next server start after a crash.
  **These rules are global while a game runs and affect non-participants too.**
* Participants' food level is kept at 20.
* Each participant's original game mode is stored individually (persistently) and restored at the end; players offline at
  the end get it back when they next join.

## Disconnects
Normal: role and prison status are kept; leaving never counts as being caught; reconnecting restores everything. If a
whole team is offline the round waits; after 5 minutes it ends with no winner. Sabotage: leavers are removed; a new seeker
is picked if none remain; fewer than 2 players ends the game. Late joiners become hiders (Adventure mode).

## Limitations
* Seeker freeze is a position lock, so frozen seekers may see slight rubber-banding if they push forward.
* A caught Normal-Mode hider must press Respawn on the death screen; the move to prison is instant afterwards.
* Name tags are hidden, but the Glowing outline (3 s after melee hits, 5 s after arrow hits) is intentionally visible.
* Gamerule changes are server-wide while a game runs.

## Verification status (honest)
| Item | Status |
|---|---|
| Versions (MC 26.3, Loader, Fabric API 0.163.0+26.3, Java 25, Loom id) | Checked against published releases/docs |
| Real Gradle build against Minecraft 26.3 / jar produced | **NOT done** (no internet/Gradle/JDK 25 where this was written) |
| Compile against hand-written API stubs | Passed - only a Java syntax/type check, **not** proof of API compatibility |
| Dedicated-server load, any in-game behaviour (freeze, timer, gamerules, respawn flow, both modes) | **NOT tested** |

Most likely first-build errors (one-line fixes): the `net.minecraft.world.level.gamerules.GameRules` import and
`GameRules.KEEP_INVENTORY / LOCATOR_BAR` constants, `ServerLevel#getRespawnData().pos()`, `FoodData#setFoodLevel`,
`PlayerTeam#setNameTagVisibility`, `ResourceKey#identifier()`, `ServerGamePacketListenerImpl#getPlayer()`. Send the
compiler output and they can be corrected.
