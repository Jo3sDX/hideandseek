package com.hideandseek;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;

/** Persistent server-side configuration, stored in config/hideandseek.json. Edited only through commands. */
public final class GameConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static final class SavedLocation {
		public String dimension;
		public double x;
		public double y;
		public double z;
		public float yaw;
		public float pitch;

		public SavedLocation() {
		}

		public static SavedLocation of(ServerPlayer player) {
			SavedLocation l = new SavedLocation();
			l.dimension = player.level().dimension().identifier().toString();
			l.x = player.getX();
			l.y = player.getY();
			l.z = player.getZ();
			l.yaw = player.getYRot();
			l.pitch = player.getXRot();
			return l;
		}

		public String describe() {
			return String.format(Locale.ROOT, "%.1f, %.1f, %.1f in %s", x, y, z, dimension);
		}
	}

	public SavedLocation prison;
	public SavedLocation lobby;
	/** Seekers picked by /hideandseek start normal when no count is given. */
	public int defaultSeekers = 1;
	/** Game time limit in minutes (starts when the seekers are released); 0 = no timer. */
	@SerializedName(value = "timeLimitMinutes", alternate = {"normalRoundMinutes"})
	public int timeLimitMinutes = 0;
	/** Seconds the seekers stay frozen at the start of a game (0 = released immediately). */
	public int prepTimeSeconds = 60;
	/** Prisoners further than this many blocks from the prison spawn are pulled back. */
	public int prisonRadius = 8;
	/**
	 * Players whose game mode was changed to ADVENTURE for prison (UUID -> ORIGINAL game mode of every participant).
	 * Persisted so that nobody stays stuck in adventure mode after a crash or restart.
	 */
	public Map<String, String> pendingRestore = new HashMap<>();
	/**
	 * Game rules changed for the duration of a game (name -> ORIGINAL value). Persisted so they are restored
	 * even after a crash. Empty while no game is running.
	 */
	public Map<String, String> savedGameRules = new HashMap<>();

	private transient Path file;

	public static GameConfig load(Path file) {
		GameConfig cfg = null;
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				cfg = GSON.fromJson(reader, GameConfig.class);
			} catch (IOException | RuntimeException e) {
				HideAndSeekMod.LOGGER.error("Could not read {}; using defaults", file, e);
			}
		}
		if (cfg == null) {
			cfg = new GameConfig();
		}
		cfg.file = file;
		if (cfg.pendingRestore == null) {
			cfg.pendingRestore = new HashMap<>();
		}
		if (cfg.savedGameRules == null) {
			cfg.savedGameRules = new HashMap<>();
		}
		if (cfg.defaultSeekers < 1) {
			cfg.defaultSeekers = 1;
		}
		if (cfg.timeLimitMinutes < 0) {
			cfg.timeLimitMinutes = 0;
		}
		if (cfg.prepTimeSeconds < 0) {
			cfg.prepTimeSeconds = 60;
		}
		if (cfg.prisonRadius < 2) {
			cfg.prisonRadius = 8;
		}
		cfg.save();
		return cfg;
	}

	public void save() {
		if (file == null) {
			return;
		}
		try {
			Path parent = file.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			HideAndSeekMod.LOGGER.error("Could not save {}", file, e);
		}
	}
}
