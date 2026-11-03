package com.hideandseek;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * All game logic. Runs exclusively on the server thread, so no locking is needed.
 *
 * Time is handled by exactly two independent mechanisms, both driven by {@link #tick()}:
 * the preparation countdown ({@link #prepEndTick}, only while PREPARING) and the game timer
 * ({@link #timerEndTick}, only while a round is active). Delayed actions use {@link #schedule}, which is
 * invalidated wholesale by {@link #generation} whenever a game starts or stops.
 *
 * A game NEVER starts by itself: every game (either mode) is started only by an admin command. When a game ends
 * (Normal or Sabotage, by timer or by win condition) it goes ROUND_ENDING -> (fixed 5 s) -> IDLE.
 */
public final class GameManager {
	/** Glowing duration applied when a seeker hits a hider: exactly 3 seconds. */
	public static final int GLOW_TICKS = 60;
	/** Fixed delay between the end of a game and the post-game cleanup. NOT configurable. */
	private static final int POST_GAME_DELAY_TICKS = 5 * 20;
	/** A hider hit by a spectral arrow keeps Glowing for 5 seconds (vanilla applies 10 s; the mod cuts it). */
	private static final int ARROW_GLOW_TICKS = 100;
	private static final int EMPTY_TEAM_TIMEOUT_SECONDS = 300;

	private record Task(long dueTick, int generation, Runnable action) {
	}

	private final MinecraftServer server;
	private final GameConfig config;
	private final Random random = new Random();

	private GameState state = GameState.IDLE;
	private Mode mode = null;
	private long tick = 0;
	/** Incremented on every start/stop so that stale scheduled tasks never run. */
	private int generation = 0;
	/** Preparation countdown: tick at which seekers are released (valid while PREPARING). */
	private long prepEndTick = 0;
	/** Game timer: tick at which the round times out, or -1 when there is no timer. */
	private long timerEndTick = -1;
	/** Tick at which the seekers were released (game timer origin). */
	private long gameStartTick = 0;
	private int emptyTeamSeconds = 0;
	/** Result message and destinations retained during the fixed post-game delay. */
	private String endingMessage;
	private final Set<UUID> endingToWorldSpawn = new HashSet<>();

	/** Everyone who takes part in the current round. Sabotage: online players only. */
	private final Map<UUID, Role> roles = new LinkedHashMap<>();
	/** Normal Mode: hiders currently in prison (subset of hider roles). */
	private final Set<UUID> jailed = new HashSet<>();
	/** Seekers frozen during preparation: UUID -> {x, z} to hold. */
	private final Map<UUID, double[]> frozen = new HashMap<>();
	/** Which equipment kit each participant currently holds; prevents duplicate equipment. */
	private final Map<UUID, Role> equipped = new HashMap<>();
	/** Hiders hit by an arrow: UUID -> tick at which the mod-managed Glowing effect is removed. */
	private final Map<UUID, Long> arrowGlowExpiry = new HashMap<>();
	/** Pre-game selections made while IDLE (used by BOTH modes). */
	private final Set<UUID> pendingSeekers = new LinkedHashSet<>();
	private final Set<UUID> pendingHiders = new LinkedHashSet<>();
	/** Players who were dead when a game ended; moved when they respawn. */
	private final Set<UUID> respawnToSpawn = new HashSet<>();
	private final Set<UUID> respawnToLobby = new HashSet<>();
	private final List<Task> tasks = new ArrayList<>();

	public GameManager(MinecraftServer server, GameConfig config) {
		this.server = server;
		this.config = config;
	}

	// ------------------------------------------------------------------ lifecycle

	public void onServerStarted() {
		// Clean up after a crash during a previous game.
		TeamManager.removeAll(server);
		restoreServerSettings();
	}

	public void shutdown() {
		if (state != GameState.IDLE) {
			stopInternal(null, false, Collections.emptySet());
		}
	}

	public GameState state() {
		return state;
	}

	// ------------------------------------------------------------------ helpers

	private boolean isActive() {
		return state == GameState.ACTIVE_NORMAL || state == GameState.ACTIVE_SABOTAGE;
	}

	private boolean canEditRoles() {
		return state == GameState.PREPARING || isActive();
	}

	private List<ServerPlayer> onlinePlayers() {
		return new ArrayList<>(server.getPlayerList().getPlayers());
	}

	private ServerPlayer player(UUID id) {
		return server.getPlayerList().getPlayer(id);
	}

	private static String nameOf(ServerPlayer p) {
		return p.getName().getString();
	}

	private static MutableComponent text(String s, ChatFormatting... formats) {
		return Component.literal(s).withStyle(formats);
	}

	private void broadcast(String message) {
		MutableComponent line = text("[Hide&Seek] ", ChatFormatting.GOLD).append(text(message, ChatFormatting.WHITE));
		server.getPlayerList().broadcastSystemMessage(line, false);
	}

	private void tell(ServerPlayer p, String message) {
		p.sendSystemMessage(text("[Hide&Seek] ", ChatFormatting.GOLD).append(text(message, ChatFormatting.WHITE)));
	}

	private void titleAll(Component title, Component subtitle) {
		for (ServerPlayer p : onlinePlayers()) {
			Notifier.title(p, title, subtitle);
		}
	}

	private void requireIdle() {
		if (state != GameState.IDLE) {
			throw new GameException("A " + mode + " game is already running (state " + state
					+ "). Use /hideandseek stop first.");
		}
	}

	private void schedule(int delayTicks, Runnable action) {
		tasks.add(new Task(tick + delayTicks, generation, action));
	}

	private void runTasks() {
		if (tasks.isEmpty()) {
			return;
		}
		List<Task> due = new ArrayList<>();
		Iterator<Task> it = tasks.iterator();
		while (it.hasNext()) {
			Task t = it.next();
			if (t.generation() != generation) {
				it.remove();
			} else if (t.dueTick() <= tick) {
				due.add(t);
				it.remove();
			}
		}
		for (Task t : due) {
			if (t.generation() == generation) {
				t.action().run();
			}
		}
	}

	private void assign(ServerPlayer p, Role role) {
		roles.put(p.getUUID(), role);
		TeamManager.assign(server, p, role);
	}

	private void applyGlow(ServerPlayer victim) {
		victim.addEffect(new MobEffectInstance(MobEffects.GLOWING, GLOW_TICKS, 0, false, false, false));
	}

	private void removeGlow(ServerPlayer p) {
		p.removeEffect(MobEffects.GLOWING);
	}

	private Entity resolveAttacker(ServerPlayer victim, DamageSource source) {
		Entity attacker = source.getEntity();
		if (attacker == null) {
			attacker = victim.getKillCredit();
		}
		return attacker;
	}

	private int count(Role role) {
		int n = 0;
		for (Role r : roles.values()) {
			if (r == role) {
				n++;
			}
		}
		return n;
	}

	private String seekerNames() {
		List<String> names = new ArrayList<>();
		for (Map.Entry<UUID, Role> e : roles.entrySet()) {
			if (e.getValue() == Role.SEEKER) {
				ServerPlayer p = player(e.getKey());
				if (p != null) {
					names.add(nameOf(p));
				}
			}
		}
		return String.join(", ", names);
	}

	private void resetRoundData() {
		generation++;
		tasks.clear();
		roles.clear();
		jailed.clear();
		frozen.clear();
		equipped.clear();
		arrowGlowExpiry.clear();
		respawnToSpawn.clear();
		respawnToLobby.clear();
		emptyTeamSeconds = 0;
		timerEndTick = -1;
		endingMessage = null;
		endingToWorldSpawn.clear();
	}

	/**
	 * Makes a participant's equipment match their role, exactly once per role:
	 * first time: clear old mod items, give 1 Ender Pearl (+ seeker kit for seekers);
	 * hider -> seeker: add the seeker kit only (pearl is never duplicated);
	 * seeker -> hider: remove the seeker kit, keep the pearl. Non-participants are never touched.
	 */
	private void syncEquipment(ServerPlayer p) {
		UUID id = p.getUUID();
		Role want = roles.get(id);
		if (want == null) {
			return;
		}
		Role have = equipped.get(id);
		if (have == want) {
			return; // already correct: a repeated callback cannot duplicate items
		}
		boolean ok = true;
		if (have == null) {
			Equipment.remove(p, true); // nothing from an earlier game can stack
			ok = Equipment.givePearl(p);
			if (want == Role.SEEKER) {
				ok &= Equipment.giveSeekerKit(server, p);
			}
		} else if (want == Role.SEEKER) {
			ok = Equipment.giveSeekerKit(server, p);
		} else {
			Equipment.remove(p, false);
		}
		equipped.put(id, want);
		if (!ok) {
			tell(p, "Your inventory is full: some game equipment could not be given.");
		}
	}

	private void expireArrowGlow() {
		if (arrowGlowExpiry.isEmpty()) {
			return;
		}
		Iterator<Map.Entry<UUID, Long>> it = arrowGlowExpiry.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Long> e = it.next();
			if (tick >= e.getValue()) {
				ServerPlayer p = player(e.getKey());
				if (p != null) {
					removeGlow(p);
				}
				it.remove();
			}
		}
	}

	private String prepDescription() {
		return config.prepTimeSeconds > 0
				? "Seekers are frozen for " + config.prepTimeSeconds + " seconds - hiders, go hide!"
				: "No preparation time: seekers are released immediately.";
	}

	private static GameType parseGameType(String name) {
		if (name != null) {
			try {
				return GameType.valueOf(name);
			} catch (IllegalArgumentException ignored) {
				// fall through
			}
		}
		return GameType.SURVIVAL;
	}

	// ------------------------------------------------------------------ game modes & server settings

	/**
	 * Puts a participant in Adventure mode. Their ORIGINAL mode is stored per player (persistently) the first
	 * time only, so deaths, respawns, role changes and reconnects can never overwrite it with ADVENTURE.
	 */
	private void enterAdventure(ServerPlayer p) {
		String key = p.getUUID().toString();
		if (!config.pendingRestore.containsKey(key)) {
			config.pendingRestore.put(key, p.gameMode().name());
			config.save();
		}
		if (p.gameMode() != GameType.ADVENTURE) {
			p.setGameMode(GameType.ADVENTURE);
		}
	}

	private void setRule(String name, String value) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
				"gamerule " + name + " " + value);
	}

	/** Temporarily: keep_inventory on (no drops, no loss) and locator_bar off. Originals are saved and restored. */
	private void applyServerSettings() {
		if (!config.savedGameRules.isEmpty()) {
			return;
		}
		ServerLevel overworld = server.overworld();
		config.savedGameRules.put("keep_inventory", String.valueOf(overworld.getGameRules().get(GameRules.KEEP_INVENTORY)));
		config.savedGameRules.put("locator_bar", String.valueOf(overworld.getGameRules().get(GameRules.LOCATOR_BAR)));
		config.save();
		setRule("keep_inventory", "true");
		setRule("locator_bar", "false");
	}

	private void restoreServerSettings() {
		if (config.savedGameRules.isEmpty()) {
			return;
		}
		for (Map.Entry<String, String> e : new HashMap<>(config.savedGameRules).entrySet()) {
			setRule(e.getKey(), e.getValue());
		}
		config.savedGameRules.clear();
		config.save();
	}

	// ------------------------------------------------------------------ starting games

	public String startNormal(Integer requested) {
		return startGame(Mode.NORMAL, requested);
	}

	public String startSabotage(Integer requested) {
		return startGame(Mode.SABOTAGE, requested);
	}

	private String startGame(Mode newMode, Integer requested) {
		requireIdle();
		if (newMode == Mode.NORMAL) {
			if (config.prison == null) {
				throw new GameException("Normal Mode cannot start: no prison is set. Stand where the prison should be "
						+ "and run /hideandseek setprison first.");
			}
			if (Locations.level(server, config.prison) == null) {
				throw new GameException("Normal Mode cannot start: the prison's dimension (" + config.prison.dimension
						+ ") is not loaded. Run /hideandseek setprison again.");
			}
		}
		List<ServerPlayer> players = onlinePlayers();
		if (players.size() < 2) {
			throw new GameException("At least 2 online players are required (1 seeker + 1 hider).");
		}
		Set<UUID> seekers = selectSeekers(players, requested);
		applyServerSettings();
		beginRound(newMode, players, seekers);
		if (config.prepTimeSeconds <= 0) {
			releaseSeekers(); // preparation time 0: no waiting, seekers are released right away
		}
		return newMode + " Mode started: " + seekers.size() + " seeker(s), " + (players.size() - seekers.size())
				+ " hider(s). " + prepDescription();
	}

	/**
	 * Seeker choice shared by BOTH modes: pre-assigned seekers (seeker command) first, then random players until
	 * the requested count (or the default count, setseekers) is reached. Players forced to be hiders are never picked.
	 */
	private Set<UUID> selectSeekers(List<ServerPlayer> players, Integer requested) {
		pendingSeekers.removeIf(id -> player(id) == null);
		pendingHiders.removeIf(id -> player(id) == null);

		int count;
		if (requested != null) {
			count = requested;
			if (pendingSeekers.size() > count) {
				throw new GameException("You pre-assigned " + pendingSeekers.size()
						+ " seekers but asked for only " + count + ". Raise the number or use /hideandseek hider.");
			}
		} else {
			count = Math.max(config.defaultSeekers, pendingSeekers.size());
			count = Math.min(count, players.size() - 1);
		}
		if (count < 1 || count >= players.size()) {
			throw new GameException("Seeker count must be between 1 and " + (players.size() - 1)
					+ " with " + players.size() + " players online.");
		}
		Set<UUID> seekers = new LinkedHashSet<>(pendingSeekers);
		List<ServerPlayer> pool = new ArrayList<>();
		for (ServerPlayer p : players) {
			if (!seekers.contains(p.getUUID()) && !pendingHiders.contains(p.getUUID())) {
				pool.add(p);
			}
		}
		Collections.shuffle(pool, random);
		for (ServerPlayer p : pool) {
			if (seekers.size() >= count) {
				break;
			}
			seekers.add(p.getUUID());
		}
		if (seekers.size() < count || seekers.size() >= players.size()) {
			throw new GameException("Not enough players are available as seekers/hiders for that selection.");
		}
		pendingSeekers.clear();
		pendingHiders.clear();
		return seekers;
	}

	/** Assigns roles and enters the preparation phase. Only ever called from an admin's start command. */
	private void beginRound(Mode newMode, List<ServerPlayer> players, Set<UUID> seekerIds) {
		resetRoundData();
		mode = newMode;
		state = GameState.PREPARING;
		TeamManager.reset(server);
		for (ServerPlayer p : players) {
			Role role = seekerIds.contains(p.getUUID()) ? Role.SEEKER : Role.HIDER;
			assign(p, role);
			enterAdventure(p);
			removeGlow(p);
			syncEquipment(p);
			if (role == Role.SEEKER && config.prepTimeSeconds > 0) {
				frozen.put(p.getUUID(), new double[] {p.getX(), p.getZ()});
			}
		}
		for (ServerPlayer p : players) {
			announceRole(p);
		}
		String names = seekerNames();
		if (newMode == Mode.SABOTAGE) {
			broadcast((seekerIds.size() == 1 ? "A new seeker has been selected: " : "New seekers have been selected: ")
					+ names + "!");
		}
		broadcast(newMode + " round! Seekers: " + names + ". " + prepDescription());
		prepEndTick = tick + config.prepTimeSeconds * 20L;
	}

	private void announceRole(ServerPlayer p) {
		Role role = roles.get(p.getUUID());
		if (role == null) {
			return;
		}
		if (role == Role.SEEKER) {
			Notifier.title(p, text("You are a SEEKER!", ChatFormatting.RED, ChatFormatting.BOLD),
					text(state == GameState.PREPARING && config.prepTimeSeconds > 0 ? "You are frozen until the countdown ends"
							: (mode == Mode.NORMAL ? "Find and catch every hider!" : "Catch everyone to convert them!"),
							ChatFormatting.RED));
		} else {
			String sub = mode == Mode.SABOTAGE ? seekerNames() + " hunt(s) you - don't get caught!"
					: "Hide from the seekers!";
			Notifier.title(p, text("You are a HIDER!", ChatFormatting.BLUE, ChatFormatting.BOLD),
					text(sub, ChatFormatting.BLUE));
		}
	}

	// ------------------------------------------------------------------ preparation (separate from the game timer)

	private void tickPreparation() {
		long remaining = prepEndTick - tick;
		if (remaining <= 0) {
			releaseSeekers();
			return;
		}
		holdSeekers();
		if (remaining % 20 == 0) {
			int secs = (int) (remaining / 20);
			for (ServerPlayer p : onlinePlayers()) {
				Role r = roles.get(p.getUUID());
				if (r == null) {
					continue;
				}
				Notifier.actionBar(p, r == Role.SEEKER
						? text("You are frozen - released in " + secs + "s", ChatFormatting.RED)
						: text("Hide! Seekers are released in " + secs + "s", ChatFormatting.YELLOW));
				if (secs == 30 || secs == 10 || secs <= 5) {
					Notifier.title(p, text(String.valueOf(secs), ChatFormatting.GOLD, ChatFormatting.BOLD),
							text("until the seekers are released", ChatFormatting.YELLOW), 0, 25, 5);
				}
			}
		}
	}

	/** Keeps frozen seekers in place horizontally (they cannot walk, sprint or be pushed away). */
	private void holdSeekers() {
		for (Map.Entry<UUID, double[]> e : frozen.entrySet()) {
			ServerPlayer p = player(e.getKey());
			if (p == null || !p.isAlive()) {
				continue;
			}
			double dx = p.getX() - e.getValue()[0];
			double dz = p.getZ() - e.getValue()[1];
			if (dx * dx + dz * dz > 0.0025) {
				ServerLevel level = server.getLevel(p.level().dimension());
				if (level != null) {
					p.teleportTo(level, e.getValue()[0], p.getY(), e.getValue()[1], Collections.emptySet(),
							p.getYRot(), p.getXRot(), true);
				}
			}
		}
	}

	/** Preparation over: release all seekers at once and start the (separate) game timer. */
	private void releaseSeekers() {
		if (state != GameState.PREPARING) {
			return;
		}
		frozen.clear();
		state = mode == Mode.NORMAL ? GameState.ACTIVE_NORMAL : GameState.ACTIVE_SABOTAGE;
		gameStartTick = tick;
		timerEndTick = config.timeLimitMinutes > 0 ? tick + config.timeLimitMinutes * 1200L : -1;
		broadcast("The seekers have been released - the hunt begins!");
		titleAll(text("SEEKERS RELEASED!", ChatFormatting.RED, ChatFormatting.BOLD),
				text("The hunt begins", ChatFormatting.YELLOW));
		reevaluate();
	}

	// ------------------------------------------------------------------ stopping

	public String stop() {
		if (state == GameState.IDLE) {
			TeamManager.removeAll(server);
			pendingSeekers.clear();
			pendingHiders.clear();
			restoreServerSettings();
			return "No game was running (pending selections cleared, leftover teams and game rules restored).";
		}
		boolean hadPrisoners = !jailed.isEmpty();
		stopInternal("The game was stopped by an admin.", true, new HashSet<>(jailed));
		String extra = hadPrisoners ? " Prisoners were sent to the world spawn." : "";
		return "Game stopped. Scheduled tasks cancelled; players, game modes and game rules restored." + extra;
	}

	/**
	 * Restores everything and returns to IDLE. Players in {@code toWorldSpawn} go to the world spawn; other
	 * participants go to the lobby if {@code toLobby} and a lobby is set. Dead players are moved when they respawn.
	 */
	private void stopInternal(String reason, boolean toLobby, Set<UUID> toWorldSpawn) {
		boolean wasRunning = state != GameState.IDLE;
		generation++; // cancels every scheduled task: nothing can restart or finish later
		tasks.clear();
		for (ServerPlayer p : onlinePlayers()) {
			UUID id = p.getUUID();
			boolean participant = roles.containsKey(id);
			if (participant) {
				removeGlow(p);
				Equipment.remove(p, true); // no temporary equipment can leak into the next game
			}
			String saved = config.pendingRestore.remove(id.toString());
			if (saved != null) {
				p.setGameMode(parseGameType(saved)); // each player's own original mode
			}
			if (!participant || !wasRunning) {
				continue;
			}
			boolean spawn = toWorldSpawn.contains(id);
			boolean lobby = !spawn && toLobby && config.lobby != null;
			if (!spawn && !lobby) {
				continue;
			}
			if (p.isAlive()) {
				if (spawn) {
					Locations.teleportToWorldSpawn(server, p);
				} else {
					Locations.teleport(server, p, config.lobby);
				}
			} else if (spawn) {
				respawnToSpawn.add(id);
			} else {
				respawnToLobby.add(id);
			}
		}
		config.save();
		TeamManager.removeAll(server);
		roles.clear();
		jailed.clear();
		frozen.clear();
		equipped.clear();
		arrowGlowExpiry.clear();
		emptyTeamSeconds = 0;
		timerEndTick = -1;
		endingMessage = null;
		endingToWorldSpawn.clear();
		restoreServerSettings();
		state = GameState.IDLE;
		mode = null;
		if (reason != null) {
			broadcast(reason);
		}
	}

	// ------------------------------------------------------------------ prison (Normal Mode)

	private void jailHider(ServerPlayer victim, ServerPlayer killer) {
		UUID id = victim.getUUID();
		if (!jailed.add(id)) {
			return; // already a prisoner: guarantees "exactly once"
		}
		Notifier.actionBar(killer, text("You caught " + nameOf(victim) + "!", ChatFormatting.RED));
		broadcast(nameOf(victim) + " was caught by " + nameOf(killer) + "!");
		evaluateNormal(false);
	}

	private void enforcePrison() {
		if (jailed.isEmpty() || config.prison == null) {
			return;
		}
		ServerLevel prisonLevel = Locations.level(server, config.prison);
		if (prisonLevel == null) {
			return;
		}
		double maxSq = (double) config.prisonRadius * config.prisonRadius;
		for (UUID id : jailed) {
			ServerPlayer p = player(id);
			if (p == null || !p.isAlive()) {
				continue;
			}
			boolean wrongDimension = !p.level().dimension().equals(prisonLevel.dimension());
			if (wrongDimension || p.distanceToSqr(config.prison.x, config.prison.y, config.prison.z) > maxSq) {
				Locations.teleport(server, p, config.prison);
			}
		}
	}

	// ------------------------------------------------------------------ win conditions & timer

	private void reevaluate() {
		if (state == GameState.ACTIVE_NORMAL) {
			evaluateNormal(false);
		} else if (state == GameState.ACTIVE_SABOTAGE) {
			evaluateSabotage();
		}
	}

	private void evaluateNormal(boolean fromTick) {
		if (state != GameState.ACTIVE_NORMAL) {
			return;
		}
		int seekersOnline = 0;
		int freeHiders = 0; // free hiders, online OR offline
		int freeHidersOnline = 0;
		int caught = 0;
		for (Map.Entry<UUID, Role> e : roles.entrySet()) {
			boolean online = player(e.getKey()) != null;
			if (e.getValue() == Role.SEEKER) {
				if (online) {
					seekersOnline++;
				}
			} else if (jailed.contains(e.getKey())) {
				caught++;
			} else {
				freeHiders++;
				if (online) {
					freeHidersOnline++;
				}
			}
		}
		// Only being caught removes a hider. A disconnected hider is still a free hider.
		if (freeHiders == 0) {
			endNormalRound(Role.SEEKER);
			return;
		}
		// If a whole team is offline the round waits. After 5 minutes it ends with no winner.
		if (seekersOnline == 0 || freeHidersOnline == 0) {
			if (fromTick) {
				emptyTeamSeconds++;
				if (emptyTeamSeconds >= EMPTY_TEAM_TIMEOUT_SECONDS) {
					endWithoutWinner("The game ended with no winner: a whole team stayed offline for 5 minutes.");
				}
			}
		} else {
			emptyTeamSeconds = 0;
		}
	}

	private void endNormalRound(Role winner) {
		if (!isActive()) {
			return; // an ending is already in progress: prevents duplicate end-of-game calls
		}
		state = GameState.ROUND_ENDING;
		timerEndTick = -1; // the game timer stops with the round
		if (winner == Role.SEEKER) {
			titleAll(text("SEEKERS WIN!", ChatFormatting.RED, ChatFormatting.BOLD),
					text("Every hider has been caught", ChatFormatting.RED));
			broadcast("SEEKERS WIN! Every hider has been caught.");
		} else {
			titleAll(text("HIDERS WIN!", ChatFormatting.BLUE, ChatFormatting.BOLD),
					text("Time ran out - the hiders survived", ChatFormatting.BLUE));
			broadcast("HIDERS WIN! Time ran out.");
		}
		finishAfterDelay(winner == Role.SEEKER
				? "Round over. Seekers won. Use /hideandseek start to play again."
				: "Round over. Hiders won. Use /hideandseek start to play again.", new HashSet<>(jailed));
	}

	/**
	 * The single end-of-game path for BOTH modes and BOTH end reasons: wait exactly 5 seconds (fixed, independent of
	 * the preparation time) and then run the cleanup. It only ever leads to IDLE: it can never start a round.
	 */
	private void finishAfterDelay(String message, Set<UUID> toWorldSpawn) {
		endingMessage = message;
		endingToWorldSpawn.clear();
		endingToWorldSpawn.addAll(toWorldSpawn);
		schedule(POST_GAME_DELAY_TICKS, this::completeGame);
	}

	private void endWithoutWinner(String message) {
		if (!isActive()) {
			return;
		}
		state = GameState.ROUND_ENDING;
		timerEndTick = -1;
		broadcast(message);
		finishAfterDelay(message + " Use /hideandseek start to play again.", new HashSet<>(jailed));
	}

	private void completeGame() {
		if (state != GameState.ROUND_ENDING) {
			return; // already cleaned up (or stopped): prevents duplicate cleanup
		}
		String message = endingMessage != null ? endingMessage
				: "Game over. Use /hideandseek start to play again.";
		Set<UUID> toWorldSpawn = new HashSet<>(endingToWorldSpawn);
		// Prisoners (Normal Mode) go to world spawn; others go to the lobby (if set).
		stopInternal(message, true, toWorldSpawn);
	}

	/** Game timer expiry. Normal: hiders win. Sabotage: the surviving hiders win and the game ends. */
	private void onTimeout() {
		timerEndTick = -1;
		if (state == GameState.ACTIVE_NORMAL) {
			endNormalRound(Role.HIDER);
		} else if (state == GameState.ACTIVE_SABOTAGE) {
			endSabotageRound(true);
		}
	}

	private void evaluateSabotage() {
		if (state != GameState.ACTIVE_SABOTAGE) {
			return;
		}
		roles.keySet().removeIf(id -> player(id) == null);
		if (roles.size() < 2) {
			endWithoutWinner("Sabotage ended: fewer than 2 players remain.");
			return;
		}
		if (count(Role.SEEKER) == 0) {
			List<ServerPlayer> hiders = new ArrayList<>();
			for (UUID id : roles.keySet()) {
				ServerPlayer p = player(id);
				if (p != null) {
					hiders.add(p);
				}
			}
			ServerPlayer chosen = hiders.get(random.nextInt(hiders.size()));
			assign(chosen, Role.SEEKER);
			syncEquipment(chosen);
			removeGlow(chosen);
			broadcast("A new seeker has been selected: " + nameOf(chosen) + "!");
			Notifier.title(chosen, text("You are a SEEKER!", ChatFormatting.RED, ChatFormatting.BOLD),
					text("The previous seeker left", ChatFormatting.RED));
		}
		if (count(Role.HIDER) == 0) {
			endSabotageRound(false);
		}
	}

	/** Sabotage ends permanently, either because every hider was converted or because the timer ran out. */
	private void endSabotageRound(boolean timeout) {
		if (!isActive()) {
			return; // an ending is already in progress: prevents duplicate end-of-game calls
		}
		state = GameState.ROUND_ENDING;
		timerEndTick = -1;
		if (timeout) {
			titleAll(text("TIME'S UP!", ChatFormatting.BLUE, ChatFormatting.BOLD),
					text("The remaining hiders survived", ChatFormatting.YELLOW));
			broadcast("Time ran out: the surviving hiders win! The game is over.");
		} else {
			titleAll(text("ALL PLAYERS HAVE BEEN CONVERTED!", ChatFormatting.RED, ChatFormatting.BOLD),
					text("SEEKERS WIN!", ChatFormatting.RED));
			broadcast("All players have become seekers! SEEKERS WIN! The game is over.");
		}
		finishAfterDelay(timeout
				? "Sabotage game over: the surviving hiders won. Use /hideandseek start to play again."
				: "Sabotage game over: all players were converted. Use /hideandseek start to play again.",
				Collections.emptySet());
	}

	private void convertInSabotage(ServerPlayer victim, ServerPlayer killer) {
		UUID id = victim.getUUID();
		if (roles.get(id) != Role.HIDER) {
			return; // already converted: guarantees no duplicate conversions
		}
		assign(victim, Role.SEEKER);
		syncEquipment(victim); // seeker kit immediately; the pearl is not given twice
		removeGlow(victim);
		Notifier.title(victim, text("You have been caught!", ChatFormatting.RED, ChatFormatting.BOLD),
				text("You are now a SEEKER!", ChatFormatting.RED));
		if (killer != null) {
			Notifier.actionBar(killer, text("You converted " + nameOf(victim) + "!", ChatFormatting.RED));
		}
		broadcast(nameOf(victim) + " was caught"
				+ (killer != null ? " by " + nameOf(killer) : "") + " and is now a seeker! Hiders left: "
				+ count(Role.HIDER));
		evaluateSabotage();
	}

	// ------------------------------------------------------------------ admin role commands

	public String seeker(ServerPlayer target) {
		String name = nameOf(target);
		UUID id = target.getUUID();
		if (state == GameState.IDLE) {
			pendingHiders.remove(id);
			pendingSeekers.add(id);
			return name + " will be a seeker in the next game, Normal or Sabotage (pre-assigned seekers: "
					+ pendingSeekers.size() + ").";
		}
		if (!canEditRoles()) {
			throw new GameException("The round is changing state; try again in a moment.");
		}
		if (roles.get(id) == Role.SEEKER) {
			throw new GameException(name + " is already a seeker.");
		}
		freeFromPrison(target);
		promote(target);
		reevaluate();
		return name + " is now a seeker.";
	}

	private void freeFromPrison(ServerPlayer target) {
		if (jailed.remove(target.getUUID()) && config.lobby != null && target.isAlive()) {
			Locations.teleport(server, target, config.lobby);
		}
	}

	private void promote(ServerPlayer target) {
		assign(target, Role.SEEKER);
		syncEquipment(target);
		removeGlow(target);
		if (state == GameState.PREPARING) {
			frozen.put(target.getUUID(), new double[] {target.getX(), target.getZ()});
		}
		Notifier.title(target, text("A new seeker has been selected!", ChatFormatting.RED, ChatFormatting.BOLD),
				text("You are now a SEEKER!", ChatFormatting.RED));
		broadcast(nameOf(target) + " is now a seeker!");
	}

	public String hider(ServerPlayer target) {
		String name = nameOf(target);
		UUID id = target.getUUID();
		if (state == GameState.IDLE) {
			pendingSeekers.remove(id);
			pendingHiders.add(id);
			return name + " will be a hider in the next game (they will never be picked randomly).";
		}
		if (!canEditRoles()) {
			throw new GameException("The round is changing state; try again in a moment.");
		}
		Role current = roles.get(id);
		if (current == Role.HIDER && !jailed.contains(id)) {
			throw new GameException(name + " is already a hider.");
		}
		if (current == Role.SEEKER) {
			int otherSeekers = 0;
			for (Map.Entry<UUID, Role> e : roles.entrySet()) {
				if (e.getValue() == Role.SEEKER && !e.getKey().equals(id) && player(e.getKey()) != null) {
					otherSeekers++;
				}
			}
			if (otherSeekers == 0) {
				throw new GameException("There must be at least one other seeker before " + name
						+ " can become a hider.");
			}
		}
		freeFromPrison(target);
		frozen.remove(id);
		assign(target, Role.HIDER);
		syncEquipment(target);
		Notifier.title(target, text("You are a HIDER!", ChatFormatting.BLUE, ChatFormatting.BOLD),
				text("An admin changed your role", ChatFormatting.BLUE));
		broadcast(name + " is now a hider.");
		reevaluate();
		return name + " is now a hider.";
	}

	public String random() {
		if (state == GameState.IDLE) {
			List<ServerPlayer> candidates = new ArrayList<>();
			for (ServerPlayer p : onlinePlayers()) {
				if (!pendingSeekers.contains(p.getUUID()) && !pendingHiders.contains(p.getUUID())) {
					candidates.add(p);
				}
			}
			if (candidates.isEmpty()) {
				throw new GameException("No eligible players to pick.");
			}
			ServerPlayer pick = candidates.get(random.nextInt(candidates.size()));
			pendingSeekers.add(pick.getUUID());
			return nameOf(pick) + " was randomly chosen as a seeker for the next game (pre-assigned seekers: "
					+ pendingSeekers.size() + ").";
		}
		if (!canEditRoles()) {
			throw new GameException("The round is changing state; try again in a moment.");
		}
		List<ServerPlayer> hiders = new ArrayList<>();
		for (Map.Entry<UUID, Role> e : roles.entrySet()) {
			ServerPlayer p = player(e.getKey());
			if (p != null && e.getValue() == Role.HIDER && !jailed.contains(e.getKey())) {
				hiders.add(p);
			}
		}
		if (hiders.size() < 2) {
			throw new GameException("At least 2 free hiders are needed to pick a random new seeker.");
		}
		ServerPlayer pick = hiders.get(random.nextInt(hiders.size()));
		promote(pick);
		reevaluate();
		return nameOf(pick) + " was randomly turned into a seeker.";
	}

	// ------------------------------------------------------------------ settings & status

	public String setPrison(ServerPlayer admin) {
		config.prison = GameConfig.SavedLocation.of(admin);
		config.save();
		return "Prison spawn saved at " + config.prison.describe() + ".";
	}

	public String setLobby(ServerPlayer admin) {
		config.lobby = GameConfig.SavedLocation.of(admin);
		config.save();
		return "Lobby spawn saved at " + config.lobby.describe() + ".";
	}

	public String setDefaultSeekers(int n) {
		config.defaultSeekers = Math.max(1, n);
		config.save();
		return "Default seeker count (Normal and Sabotage) is now " + config.defaultSeekers + ".";
	}

	public String setPrepTime(int seconds) {
		if (seconds < 0) {
			throw new GameException("The preparation time cannot be negative (got " + seconds
					+ "). Use 0 for no preparation, or a number of seconds up to 3600.");
		}
		if (seconds > 3600) {
			throw new GameException("The preparation time is too long (max 3600 seconds).");
		}
		config.prepTimeSeconds = seconds;
		config.save();
		String when = state == GameState.PREPARING
				? " It applies to the next game; the preparation already running is not changed."
				: "";
		return (seconds == 0
				? "Preparation time set to 0: seekers will be released immediately when a game starts."
				: "Preparation time set to " + seconds + " second(s): seekers stay frozen that long at the start of a game.")
				+ when;
	}

	public String setRoundMinutes(int minutes) {
		config.timeLimitMinutes = Math.max(0, minutes);
		config.save();
		if (isActive()) { // re-base the single running timer
			timerEndTick = config.timeLimitMinutes > 0 ? gameStartTick + config.timeLimitMinutes * 1200L : -1;
		}
		return config.timeLimitMinutes == 0
				? "Game timer disabled."
				: "Game timer set to " + config.timeLimitMinutes + " minute(s), counted from the moment the seekers "
						+ "are released. On timeout the hiders win and the game ends (Normal and Sabotage).";
	}

	public List<String> statusLines() {
		List<String> lines = new ArrayList<>();
		lines.add("Mode: " + (mode == null ? "none" : mode) + " | State: " + state);
		if (state != GameState.IDLE) {
			String prison = mode == Mode.NORMAL ? " (" + jailed.size() + " in prison)" : "";
			lines.add("Hiders: " + count(Role.HIDER) + prison + " | Seekers: " + count(Role.SEEKER));
			if (state == GameState.PREPARING) {
				lines.add("Seekers released in " + Math.max(0, (prepEndTick - tick + 19) / 20) + "s");
			} else if (timerEndTick >= 0) {
				lines.add("Time remaining: " + formatTime(Math.max(0, (timerEndTick - tick + 19) / 20)));
			}
		} else if (!pendingSeekers.isEmpty() || !pendingHiders.isEmpty()) {
			lines.add("Pre-assigned seekers: " + pendingSeekers.size() + " | Pre-assigned hiders: "
					+ pendingHiders.size());
		}
		lines.add("Prison: " + (config.prison == null ? "NOT SET" : config.prison.describe()));
		lines.add("Lobby: " + (config.lobby == null ? "not set" : config.lobby.describe()));
		lines.add("Default seekers: " + config.defaultSeekers + " | Time limit: "
				+ (config.timeLimitMinutes == 0 ? "none" : config.timeLimitMinutes + " min"));
		lines.add("Preparation time: " + config.prepTimeSeconds + " s | Post-game delay: 5 s (fixed)");
		return lines;
	}

	private static String formatTime(long totalSeconds) {
		return String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60, totalSeconds % 60);
	}

	// ------------------------------------------------------------------ event hooks

	/** ALLOW_DAMAGE: returns false to cancel the damage. Also applies the 3-second glow. */
	public boolean allowDamage(LivingEntity entity, DamageSource source) {
		if (state == GameState.IDLE) {
			return true;
		}
		Entity attacker = source.getEntity();
		ServerPlayer attackerPlayer = attacker instanceof ServerPlayer sp ? sp : null;
		if (attackerPlayer != null && jailed.contains(attackerPlayer.getUUID())) {
			return false; // prisoners cannot hurt anyone
		}
		if (!(entity instanceof ServerPlayer victim)) {
			return true;
		}
		if (jailed.contains(victim.getUUID())) {
			return false; // prisoners cannot be hurt
		}
		if (attackerPlayer == null) {
			return true;
		}
		if (!isActive()) {
			// no player-vs-player damage while preparing / ending / resetting
			return !(roles.containsKey(attackerPlayer.getUUID()) && roles.containsKey(victim.getUUID()));
		}
		if (roles.get(attackerPlayer.getUUID()) == Role.SEEKER && roles.get(victim.getUUID()) == Role.HIDER) {
			Entity direct = source.getDirectEntity();
			UUID victimId = victim.getUUID();
			if (direct != null && direct.getClass().getSimpleName().equals("Arrow")) {
				// Normal arrows do not glow targets in vanilla; apply the requested 5-second effect ourselves.
				arrowGlowExpiry.put(victimId, tick + ARROW_GLOW_TICKS + 1);
				victim.addEffect(new MobEffectInstance(MobEffects.GLOWING, ARROW_GLOW_TICKS, 0, false, false, false));
			} else {
				// A melee hit refreshes 3 s, but must not shorten an arrow glow already in progress.
				Long arrowExpiry = arrowGlowExpiry.get(victimId);
				int duration = arrowExpiry == null ? GLOW_TICKS : (int) Math.max(GLOW_TICKS, arrowExpiry - tick);
				victim.addEffect(new MobEffectInstance(MobEffects.GLOWING, duration, 0, false, false, false));
			}
		}
		return true;
	}

	/**
	 * AFTER_DEATH (once per death). Normal: the dead hider becomes a prisoner and is moved to the prison when they
	 * respawn. Sabotage: the dead hider is converted to a seeker immediately. Inventories are kept by keep_inventory.
	 */
	public void afterDeath(LivingEntity entity, DamageSource source) {
		if (!(entity instanceof ServerPlayer victim) || !isActive()) {
			return;
		}
		UUID id = victim.getUUID();
		if (roles.get(id) != Role.HIDER || jailed.contains(id)) {
			return;
		}
		Entity attacker = resolveAttacker(victim, source);
		if (!(attacker instanceof ServerPlayer killer) || roles.get(killer.getUUID()) != Role.SEEKER) {
			return;
		}
		if (state == GameState.ACTIVE_NORMAL) {
			jailHider(victim, killer);
		} else {
			convertInSabotage(victim, killer);
		}
	}

	/** AFTER_RESPAWN: the new player entity is re-configured (Adventure mode, prison, frozen position). */
	public void onRespawn(ServerPlayer player) {
		UUID id = player.getUUID();
		if (respawnToSpawn.remove(id)) {
			Locations.teleportToWorldSpawn(server, player);
			return;
		}
		if (respawnToLobby.remove(id)) {
			if (config.lobby != null) {
				Locations.teleport(server, player, config.lobby);
			}
			return;
		}
		if (state == GameState.IDLE || !roles.containsKey(id)) {
			return;
		}
		enterAdventure(player);
		if (jailed.contains(id) && config.prison != null) {
			Locations.teleport(server, player, config.prison);
			Notifier.title(player, text("You have been caught!", ChatFormatting.RED, ChatFormatting.BOLD),
					text("You are in prison until the round ends", ChatFormatting.GRAY));
		} else if (frozen.containsKey(id)) {
			frozen.put(id, new double[] {player.getX(), player.getZ()});
		}
	}

	public void onJoin(ServerPlayer player) {
		UUID id = player.getUUID();
		if (state == GameState.ROUND_ENDING) {
			Equipment.remove(player, true);
			restoreLeftover(player);
			return;
		}
		if (state == GameState.IDLE || mode == null) {
			Equipment.remove(player, true); // leftover game items from a game that ended while they were offline
			restoreLeftover(player);
			return;
		}
		Role role = roles.get(id);
		boolean lateJoin = role == null;
		if (lateJoin) {
			role = Role.HIDER;
			roles.put(id, role);
		}
		TeamManager.assign(server, player, role);
		syncEquipment(player);
		enterAdventure(player); // original mode stays stored from the first time
		if (jailed.contains(id)) {
			Locations.teleport(server, player, config.prison);
		} else if (role == Role.SEEKER && state == GameState.PREPARING) {
			frozen.put(id, new double[] {player.getX(), player.getZ()});
		}
		schedule(20, () -> {
			ServerPlayer p = player(id);
			if (p != null && roles.containsKey(id)) {
				announceRole(p);
				if (lateJoin) {
					tell(p, "A " + mode + " game is in progress. You joined as a hider.");
				}
			}
		});
	}

	public void onDisconnect(ServerPlayer player) {
		UUID id = player.getUUID();
		if (state == GameState.IDLE) {
			pendingSeekers.remove(id);
			pendingHiders.remove(id);
			return;
		}
		if (mode == Mode.SABOTAGE) {
			roles.remove(id);
			frozen.remove(id);
			equipped.remove(id);
			arrowGlowExpiry.remove(id);
			TeamManager.remove(server, player);
		}
		// Normal Mode keeps the role and prison status so a reconnecting player continues where they left off.
		schedule(1, this::reevaluate);
	}

	/** Undo Adventure mode for a player left behind by a crash or by being offline when the game ended. */
	private void restoreLeftover(ServerPlayer player) {
		String saved = config.pendingRestore.remove(player.getUUID().toString());
		if (saved == null) {
			return;
		}
		config.save();
		player.setGameMode(parseGameType(saved));
		if (config.lobby != null) {
			Locations.teleport(server, player, config.lobby);
		} else {
			Locations.teleportToWorldSpawn(server, player);
		}
		tell(player, "The game ended while you were offline; you were returned to normal.");
	}

	public void tick() {
		tick++;
		runTasks();
		if (state == GameState.IDLE) {
			return;
		}
		if (state == GameState.PREPARING) {
			tickPreparation();
		}
		expireArrowGlow();
		if (tick % 10 == 0) {
			enforcePrison();
			keepFed();
		}
		if (isActive()) {
			if (timerEndTick >= 0 && tick >= timerEndTick) {
				onTimeout();
			}
			if (tick % 20 == 0) {
				evaluateNormal(true);
				sendActionBars();
			}
		}
	}

	/** Hunger never drops for participants. */
	private void keepFed() {
		for (UUID id : roles.keySet()) {
			ServerPlayer p = player(id);
			if (p != null && p.isAlive()) {
				p.getFoodData().setFoodLevel(20);
			}
		}
	}

	/** The one place the timer is displayed: once per second, "Time remaining: mm:ss". */
	private void sendActionBars() {
		if (!isActive()) {
			return;
		}
		int hiders = count(Role.HIDER);
		int seekers = count(Role.SEEKER);
		String counts = state == GameState.ACTIVE_NORMAL
				? "Hiders free: " + (hiders - jailed.size()) + "  |  Caught: " + jailed.size() + "  |  Seekers: " + seekers
				: "Hiders left: " + hiders + "  |  Seekers: " + seekers;
		String line = timerEndTick >= 0
				? "Time remaining: " + formatTime(Math.max(0, (timerEndTick - tick + 19) / 20)) + "  |  " + counts
				: counts;
		Component bar = text(line, ChatFormatting.YELLOW);
		for (ServerPlayer p : onlinePlayers()) {
			if (roles.containsKey(p.getUUID())) {
				Notifier.actionBar(p, bar);
			}
		}
	}
}
