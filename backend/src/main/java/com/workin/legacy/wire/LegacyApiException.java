package com.workin.legacy.wire;

import java.util.Map;

/**
 * One legacy {@code fail($message, $status_code, $data, $replace)} call
 * ({@code functions.php:387}), as an exception.
 *
 * <p>Distinct from {@link com.workin.backend.i18n.ApiException} because the two
 * render different wire contracts: {@code ApiException} produces
 * {@code {code, message}}, this produces the PHP envelope (D-074). The legacy
 * request guard still throws {@code ApiException} -- it predates this boundary
 * and is shared with the merged {@code /api/legacy/**} modules -- so
 * {@link LegacyWireExceptionHandler} translates those as well, using the
 * message key as the envelope's message key exactly as PHP does.
 *
 * @param status the HTTP status PHP passes to {@code fail()}
 * @param messageKey the {@code LangKey} constant, translated by {@link LegacyMessages}
 * @param data the optional {@code data} payload ({@code null} omits the key)
 * @param replace {@code t()}'s placeholder map ({@code {name}} substitutions)
 */
public class LegacyApiException extends RuntimeException {

	private final int status;
	private final String messageKey;
	private final transient Object data;
	private final transient Map<String, Object> replace;

	public LegacyApiException(int status, String messageKey) {
		this(status, messageKey, null, Map.of());
	}

	public LegacyApiException(int status, String messageKey, Object data) {
		this(status, messageKey, data, Map.of());
	}

	public LegacyApiException(int status, String messageKey, Object data, Map<String, Object> replace) {
		super(messageKey);
		this.status = status;
		this.messageKey = messageKey;
		this.data = data;
		this.replace = replace == null ? Map.of() : replace;
	}

	public int getStatus() {
		return status;
	}

	public String getMessageKey() {
		return messageKey;
	}

	public Object getData() {
		return data;
	}

	public Map<String, Object> getReplace() {
		return replace;
	}

}
