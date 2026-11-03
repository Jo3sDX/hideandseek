package com.hideandseek;

/** The game-state machine. Exactly one value is active at any time. */
public enum GameState {
	/** No game running. */
	IDLE,
	/** Roles assigned; configurable preparation (default 60 s). Seekers are frozen, hiders hide. No game timer yet. */
	PREPARING,
	/** Normal Mode round in progress (seekers released). */
	ACTIVE_NORMAL,
	/** Sabotage Mode round in progress (seekers released). */
	ACTIVE_SABOTAGE,
	/** The game has been decided and announced; waiting a fixed 5 seconds before the post-game cleanup. */
	ROUND_ENDING
}
