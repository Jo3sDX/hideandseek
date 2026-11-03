package com.hideandseek;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;

/** Title / subtitle / action bar helpers. */
final class Notifier {
	private Notifier() {
	}

	static void title(ServerPlayer player, Component title, Component subtitle) {
		title(player, title, subtitle, 10, 60, 20);
	}

	static void title(ServerPlayer player, Component title, Component subtitle, int fadeIn, int stay, int fadeOut) {
		player.connection.send(new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
		player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
		player.connection.send(new ClientboundSetTitleTextPacket(title));
	}

	static void actionBar(ServerPlayer player, Component message) {
		player.sendSystemMessage(message, true);
	}
}
