package com.hideandseek;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;

/** /hideandseek ... (operators / game masters only). */
final class HideAndSeekCommand {
	private HideAndSeekCommand() {
	}

	@FunctionalInterface
	private interface Action {
		String apply(GameManager game) throws CommandSyntaxException;
	}

	static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("hideandseek")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("start")
						.then(Commands.literal("normal")
								.executes(c -> run(c, g -> g.startNormal(null)))
								.then(Commands.argument("seekers", IntegerArgumentType.integer(1, 64))
										.executes(c -> run(c,
												g -> g.startNormal(IntegerArgumentType.getInteger(c, "seekers"))))))
						.then(Commands.literal("sabotage")
								.executes(c -> run(c, g -> g.startSabotage(null)))
								.then(Commands.argument("seekers", IntegerArgumentType.integer(1, 64))
										.executes(c -> run(c,
												g -> g.startSabotage(IntegerArgumentType.getInteger(c, "seekers")))))))
				.then(Commands.literal("stop")
						.executes(c -> run(c, GameManager::stop)))
				.then(Commands.literal("random")
						.executes(c -> run(c, GameManager::random)))
				.then(Commands.literal("seeker")
						.then(Commands.argument("player", EntityArgument.player())
								.executes(c -> run(c, g -> g.seeker(EntityArgument.getPlayer(c, "player"))))))
				.then(Commands.literal("hider")
						.then(Commands.argument("player", EntityArgument.player())
								.executes(c -> run(c, g -> g.hider(EntityArgument.getPlayer(c, "player"))))))
				.then(Commands.literal("setprison")
						.executes(c -> run(c, g -> g.setPrison(c.getSource().getPlayerOrException()))))
				.then(Commands.literal("setlobby")
						.executes(c -> run(c, g -> g.setLobby(c.getSource().getPlayerOrException()))))
				.then(Commands.literal("setseekers")
						.then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
								.executes(c -> run(c, g -> g.setDefaultSeekers(IntegerArgumentType.getInteger(c, "count"))))))
				.then(Commands.literal("settimelimit")
						.then(Commands.argument("minutes", IntegerArgumentType.integer(0, 600))
								.executes(c -> run(c, g -> g.setRoundMinutes(IntegerArgumentType.getInteger(c, "minutes"))))))
				.then(Commands.literal("setpreptime")
						.then(Commands.argument("seconds", IntegerArgumentType.integer())
								.executes(c -> run(c, g -> g.setPrepTime(IntegerArgumentType.getInteger(c, "seconds"))))))
				.then(Commands.literal("status")
						.executes(HideAndSeekCommand::status))
				.then(Commands.literal("help")
						.executes(HideAndSeekCommand::help))
				.executes(HideAndSeekCommand::help));
	}

	private static int run(CommandContext<CommandSourceStack> ctx, Action action) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		GameManager game = HideAndSeekMod.game();
		if (game == null) {
			source.sendFailure(Component.literal("Hide and Seek is not ready yet (server still starting)."));
			return 0;
		}
		try {
			String message = action.apply(game);
			if (message != null) {
				source.sendSuccess(() -> Component.literal(message).withStyle(ChatFormatting.GREEN), true);
			}
			return 1;
		} catch (GameException e) {
			source.sendFailure(Component.literal(e.getMessage()));
			return 0;
		}
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		GameManager game = HideAndSeekMod.game();
		if (game == null) {
			source.sendFailure(Component.literal("Hide and Seek is not ready yet."));
			return 0;
		}
		for (String line : game.statusLines()) {
			source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.YELLOW), false);
		}
		return 1;
	}

	private static final String[] HELP = {
			"/hideandseek start normal [seekers] - Start Normal Mode (needs a prison). Seekers wait for the preparation time, then the hunt.",
			"/hideandseek start sabotage [seekers] - Start Sabotage Mode (caught hiders become seekers). Never restarts by itself.",
			"/hideandseek stop - Stop now: cancels everything and restores players, game modes and game rules.",
			"/hideandseek random - Idle: pre-assign a random seeker. In a game: turn a random free hider into a seeker.",
			"/hideandseek seeker <player> - Idle: pre-assign as seeker (any mode). In a game: make the player a seeker.",
			"/hideandseek hider <player> - Idle: force as hider. In a game: make the player a hider (frees prisoners).",
			"/hideandseek setseekers <n> - Default number of seekers for both modes.",
			"/hideandseek settimelimit <minutes> - Game timer in minutes (0 = off). Starts when seekers are released.",
			"/hideandseek setpreptime <seconds> - Seeker preparation time at the start of a game (default 60, 0 = none).",
			"/hideandseek setprison - Save your position as the Normal Mode prison.",
			"/hideandseek setlobby - Save your position as the lobby.",
			"/hideandseek status - Show mode, state, counts, timer and saved locations.",
			"/hideandseek help - Show this list."
	};

	private static int help(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		source.sendSuccess(() -> Component.literal("Hide and Seek commands:").withStyle(ChatFormatting.GOLD), false);
		for (String line : HELP) {
			source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
		}
		return 1;
	}
}
