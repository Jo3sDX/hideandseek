# Hide & Seek Plus

A multiplayer Hide and Seek minigame for **Minecraft Java 26.3**, made with Fabric.

### Game modes

**Normal Mode**
- Hiders try to survive until time runs out.
- Caught Hiders are sent to prison.
- Seekers win when no free Hiders remain.

**Sabotage Mode**
- Caught Hiders become Seekers.
- The Seeker team grows as the game progresses.
- Seekers win when everyone has been caught.

Games are started manually by an administrator. No automatic rounds!

### Features

- Two different game modes.
- Customizable preparation time and game time limit.
- Choose how many Seekers there are.
- Assign players to teams manually or randomly.
- Custom lobby and prison locations.
- Automatic equipment for each team.
- Hidden player nametags.
- Player roles are preserved when reconnecting.
- Players return to the lobby after respawning.
- Automatic cleanup when a game ends.

### Requirements

| | Version |
|---|---|
| Minecraft | Java Edition 26.3 |
| Java | 25 |
| Fabric Loader | 0.19.5+ |
| Fabric API | 0.163.0+26.3 |

**Server-side only:** Only the server needs this mod. Players can join using a normal vanilla Minecraft client.

### Installation

1. Install [Fabric Server](https://fabricmc.net/use/server/) for Minecraft 26.3.
2. Download [Fabric API](https://modrinth.com/mod/fabric-api).
3. Download the latest Hide and Seek `.jar` file.
4. Put both `.jar` files into your server's `mods` folder.
5. Start the server.

Before playing, set your lobby and prison locations using the commands below.

### Commands

All commands require operator permissions.

| Command | Description |
|---|---|
| `/hideandseek help` | Show all commands |
| `/hideandseek status` | Check the game status |
| `/hideandseek setlobby` | Set the lobby location |
| `/hideandseek setprison` | Set the prison location |
| `/hideandseek start normal` | Start Normal Mode |
| `/hideandseek start sabotage` | Start Sabotage Mode |
| `/hideandseek stop` | Stop the current game |
| `/hideandseek seeker <player>` | Assign a Seeker |
| `/hideandseek hider <player>` | Assign a Hider |
| `/hideandseek random` | Pick a random Seeker |
| `/hideandseek setseekers <number>` | Set the default Seeker count |
| `/hideandseek setpreptime <seconds>` | Set preparation time |
| `/hideandseek settimelimit <minutes>` | Set the game time limit |

Set preparation time or game time limit to `0` to disable it.

### How to play

1. An admin sets the lobby and prison locations.
2. An admin starts a game and chooses the mode.
3. Players are sent to the lobby and assigned their roles.
4. Hiders get time to hide while Seekers prepare.
5. Seekers are released and the game begins.
6. The winning team is announced when the game ends.

After the game, players are returned to the world spawn and their original game modes are restored.

### Additional information

- Prisoners in Normal Mode can leave the prison. Use barrier blocks if you want to keep them inside.
- Game rules such as PvP and keep inventory are changed while a game is running.
- Game state does not survive server restarts.

### Building from source

Requires JDK 25.

**Windows:**
```powershell
gradlew.bat build
```

**Linux / macOS:**
```bash
./gradlew build
```

The compiled JAR will be in `build/libs/`.

### Support

Found a bug or have an idea? Open an issue on GitHub or join our Discord.
