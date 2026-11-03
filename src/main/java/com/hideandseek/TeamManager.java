package com.hideandseek;

import java.util.Optional;
import net.minecraft.world.scores.TeamColor;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

/** Owns the two scoreboard teams: hiders (blue) and seekers (red). */
final class TeamManager {
	static final String HIDERS = "hs_hiders";
	static final String SEEKERS = "hs_seekers";

	private TeamManager() {
	}

	private static String nameOf(Role role) {
		return role == Role.HIDER ? HIDERS : SEEKERS;
	}

	private static PlayerTeam team(MinecraftServer server, Role role) {
		Scoreboard sb = server.getScoreboard();
		String name = nameOf(role);
		PlayerTeam team = sb.getPlayerTeam(name);
		if (team == null) {
			team = sb.addPlayerTeam(name);
		}
		team.setColor(Optional.of(role == Role.HIDER ? TeamColor.BLUE : TeamColor.RED));
		team.setDisplayName(Component.literal(role == Role.HIDER ? "Hiders" : "Seekers"));
		team.setAllowFriendlyFire(false);
		team.setNameTagVisibility(Team.Visibility.NEVER);
		return team;
	}

	/** Puts the player on the team for the role (removing them from the other team automatically). */
	static void assign(MinecraftServer server, ServerPlayer player, Role role) {
		Scoreboard sb = server.getScoreboard();
		PlayerTeam other = sb.getPlayerTeam(nameOf(role == Role.HIDER ? Role.SEEKER : Role.HIDER));
		String entry = player.getScoreboardName();
		if (other != null && other.getPlayers().contains(entry)) {
			sb.removePlayerFromTeam(entry, other);
		}
		sb.addPlayerToTeam(entry, team(server, role));
	}

	static void remove(MinecraftServer server, ServerPlayer player) {
		Scoreboard sb = server.getScoreboard();
		String entry = player.getScoreboardName();
		for (String teamName : new String[] {HIDERS, SEEKERS}) {
			PlayerTeam t = sb.getPlayerTeam(teamName);
			if (t != null && t.getPlayers().contains(entry)) {
				sb.removePlayerFromTeam(entry, t);
			}
		}
	}

	/** Deletes both teams, which also clears every member (online or offline). */
	static void removeAll(MinecraftServer server) {
		Scoreboard sb = server.getScoreboard();
		for (String teamName : new String[] {HIDERS, SEEKERS}) {
			PlayerTeam t = sb.getPlayerTeam(teamName);
			if (t != null) {
				sb.removePlayerTeam(t);
			}
		}
	}

	/** Fresh, empty teams. */
	static void reset(MinecraftServer server) {
		removeAll(server);
		team(server, Role.HIDER);
		team(server, Role.SEEKER);
	}
}
