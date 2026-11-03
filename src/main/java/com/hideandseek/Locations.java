package com.hideandseek;

import java.util.Collections;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

final class Locations {
	private Locations() {
	}

	/** Resolves the saved dimension to a loaded level, or null. */
	static ServerLevel level(MinecraftServer server, GameConfig.SavedLocation loc) {
		if (loc == null || loc.dimension == null) {
			return null;
		}
		try {
			ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(loc.dimension));
			return server.getLevel(key);
		} catch (RuntimeException e) {
			return null;
		}
	}

	static boolean teleport(MinecraftServer server, ServerPlayer player, GameConfig.SavedLocation loc) {
		ServerLevel level = level(server, loc);
		if (level == null) {
			return false;
		}
		player.teleportTo(level, loc.x, loc.y, loc.z, Collections.emptySet(), loc.yaw, loc.pitch, true);
		return true;
	}

	/** Teleports to the real world spawn (the overworld's respawn data). */
	static boolean teleportToWorldSpawn(MinecraftServer server, ServerPlayer player) {
		ServerLevel level = server.overworld();
		if (level == null) {
			return false;
		}
		var spawn = level.getRespawnData().pos();
		player.teleportTo(level, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, Collections.emptySet(),
				player.getYRot(), player.getXRot(), true);
		return true;
	}
}
