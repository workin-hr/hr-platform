package com.workin.backend.i18n;

/**
 * Typed constants for every key in the i18n catalogs
 * (src/main/resources/i18n/messages*.properties).
 * MessageCatalogSyncTest enforces that each constant resolves in the
 * English base and that every translation has full key parity. Never
 * introduce a message string anywhere else.
 */
public final class MessageKeys {

	public static final String ERROR_NOT_FOUND = "error.not_found";
	public static final String ERROR_FORBIDDEN = "error.forbidden";
	public static final String ERROR_UNAUTHORIZED = "error.unauthorized";
	public static final String ERROR_CONFLICT = "error.conflict";
	public static final String ERROR_BAD_REQUEST = "error.bad_request";
	public static final String ERROR_VALIDATION = "error.validation";

	private MessageKeys() {
	}

}
