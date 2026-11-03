package com.hideandseek;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HideAndSeekMod implements ModInitializer {
	public static final String MOD_ID = "hideandseek";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static GameConfig config;
	private static GameManager game;

	/** The active game manager, or null while no server is running. */
	public static GameManager game() {
		return game;
	}

	@Override
	public void onInitialize() {
		config = GameConfig.load(FabricLoader.getInstance().getConfigDir().resolve("hideandseek.json"));

		CommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess, environment) -> HideAndSeekCommand.register(dispatcher));

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			game = new GameManager(server, config);
			game.onServerStarted();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (game != null) {
				game.shutdown();
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> game = null);

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (game != null) {
				game.tick();
			}
		});

		ServerLivingEntityEvents.ALLOW_DAMAGE.register(
				(entity, source, amount) -> game == null || game.allowDamage(entity, source));
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (game != null) {
				game.afterDeath(entity, source);
			}
		});

		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (game != null) {
				game.onRespawn(newPlayer);
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (game != null) {
				game.onJoin(handler.getPlayer());
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (game != null) {
				game.onDisconnect(handler.getPlayer());
			}
		});

		LOGGER.info("Hide and Seek loaded.");
	}
}
