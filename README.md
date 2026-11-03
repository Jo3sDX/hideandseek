# Hide and Seek

A server-side Fabric mod that adds a multiplayer Hide and Seek minigame to Minecraft Java Edition 26.3.

## Features

- **Normal Mode** — Seekers hunt Hiders. Caught Hiders are sent to prison.
- **Sabotage Mode** — Caught Hiders are converted into Seekers.
- **Configurable preparation time** — Choose how long Seekers must wait before being released. Set it to 0 for immediate release.
- **Configurable game time limit** — Set a time limit or disable it.
- **Custom equipment** — Every participant receives one Ender Pearl. Seekers receive an Infinity bow, one normal arrow, and a Sharpness IV stone sword.
- **Temporary glowing effect** — The arrow applies a short glowing effect when it hits a player.
- **Team management** — Assign Seekers and Hiders manually or select them automatically.
- **Prison and lobby locations** — Configure locations for the minigame.
- **Automatic cleanup** — Game-related effects, equipment and temporary settings are cleaned up when a game ends.
- **No automatic restarts** — Every new game must be started manually by an administrator.

## Requirements

- Minecraft Java Edition 26.3
- Fabric Loader
- Fabric API

## Installation

1. Install Fabric Loader on your Minecraft server.
2. Install Fabric API.
3. Download the mod `.jar` file.
4. Place the `.jar` inside your server's `mods` folder.
5. Start the server.

## Commands

All commands use the `/hideandseek` prefix.

| Command | Description |
|---|---|
| `/hideandseek help` | Display available commands |
| `/hideandseek status` | Display current game status and settings |
| `/hideandseek start normal` | Start Normal mode |
| `/hideandseek start sabotage` | Start Sabotage mode |
| `/hideandseek stop` | Stop the current game |
| `/hideandseek setseekers <number>` | Set the default number of Seekers |
| `/hideandseek seeker <player>` | Assign a player as a Seeker |
| `/hideandseek hider <player>` | Assign a player as a Hider |
| `/hideandseek setpreptime <seconds>` | Set the Seeker preparation time |
| `/hideandseek settimelimit <minutes>` | Set the game time limit |
| `/hideandseek setprison` | Set the prison location |
| `/hideandseek setlobby` | Set the lobby location |

## Game behaviour

- Seekers remain frozen during preparation.
- Hiders can move during preparation.
- The game timer starts when Seekers are released.
- After a game ends, cleanup happens after a fixed 5-second delay.
- Games never restart automatically.

## Development

The source code is available in this repository. The project uses Gradle and Fabric Loom.

## Issues

If you encounter a bug, please open a GitHub issue and include:
- Minecraft version
- Fabric Loader version
- Fabric API version
- Relevant server log output
I will help you out :)

## Version

**1.0.0 — Initial stable release**
