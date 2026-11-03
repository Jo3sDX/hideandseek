package com.hideandseek;

/** A user-facing error (shown to the admin in chat). */
public final class GameException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public GameException(String message) {
		super(message);
	}
}
