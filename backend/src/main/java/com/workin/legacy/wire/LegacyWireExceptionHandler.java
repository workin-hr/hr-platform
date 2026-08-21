package com.workin.legacy.wire;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.workin.backend.i18n.ApiException;

/**
 * Renders the PHP envelope for the endpoints that serve legacy's own routes
 * (D-074). Scoped to {@code com.workin.legacy.employees} on purpose: the merged
 * {@code /api/legacy/**} modules keep rendering
 * {@code com.workin.backend.i18n.ApiErrorBody} until the retroactive contract
 * audit D-074 requires, and the PostgreSQL surface is untouched. Ordered ahead
 * of {@code ApiExceptionHandler} so this advice wins for that package while the
 * global one still serves everything else.
 */
@RestControllerAdvice(basePackages = "com.workin.legacy.employees")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LegacyWireExceptionHandler {

	private final LegacyMessages messages;

	public LegacyWireExceptionHandler(LegacyMessages messages) {
		this.messages = messages;
	}

	/** One {@code fail($message, $status, $data, $replace)} call. */
	@ExceptionHandler(LegacyApiException.class)
	public ResponseEntity<LegacyApiResponse> handleLegacy(LegacyApiException ex, HttpServletRequest request) {
		String text = messages.translate(messages.resolveLocale(request), ex.getMessageKey(), ex.getReplace());
		return ResponseEntity.status(ex.getStatus()).body(LegacyApiResponse.fail(text, ex.getData()));
	}

	/**
	 * {@link com.workin.legacy.auth.LegacyRequestGuard} and
	 * {@link com.workin.legacy.authorization.LegacyHrPermissionEnforcer} predate
	 * this boundary and throw {@code ApiException} with the legacy message key
	 * as their code -- {@code session_replaced},
	 * {@code forbidden_insufficient_role}, {@code company_account_not_active},
	 * {@code unauthorized_no_token}, {@code unauthorized_invalid_token}. The
	 * guard stack is shared with the merged modules, so it is translated here
	 * rather than rewritten: same key, same status, PHP's envelope.
	 */
	@ExceptionHandler(ApiException.class)
	public ResponseEntity<LegacyApiResponse> handlePlatform(ApiException ex, HttpServletRequest request) {
		String text = messages.translate(messages.resolveLocale(request), ex.getCode(), null);
		return ResponseEntity.status(ex.getStatus()).body(LegacyApiResponse.fail(text, null));
	}

}
