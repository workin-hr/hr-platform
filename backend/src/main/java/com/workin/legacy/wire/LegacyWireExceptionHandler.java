package com.workin.legacy.wire;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.workin.backend.i18n.ApiException;

/**
 * Renders the PHP envelope for the endpoints that serve legacy's own routes
 * (D-074). Scoped to the packages that carry those routes on purpose: the
 * merged {@code /api/legacy/**} modules keep rendering
 * {@code com.workin.backend.i18n.ApiErrorBody} until the retroactive contract
 * audit D-074 requires, and the PostgreSQL surface is untouched. Ordered ahead
 * of {@code ApiExceptionHandler} so this advice wins for those packages while
 * the global one still serves everything else.
 *
 * <p>The list grows one wave at a time, alongside
 * {@link LegacyPhpRoutes#CONTROLLER_GUARDED} and for the same reason: a module
 * belongs here once its controller maps literal {@code *.php} routes and raises
 * {@link LegacyApiException}. A module added to the routes list but missed here
 * would authenticate correctly and then answer every error with the platform
 * body instead of PHP's -- silently, and only on the failure paths.
 *
 * <ul>
 * <li>{@code com.workin.legacy.employees} -- Wave 12.4</li>
 * <li>{@code com.workin.legacy.workforce} -- Wave 12.5</li>
 * <li>{@code com.workin.legacy.attendance.records} -- Wave 12.6</li>
 * </ul>
 *
 * <p>The last entry is a <b>subpackage</b>, and deliberately so. Wave 12.1's
 * {@code LegacyExceptionTypeController} sits in the parent
 * {@code com.workin.legacy.attendance}, serves the merged
 * {@code /api/legacy/**} surface and raises {@code ApiException} -- which this
 * advice also handles. Listing the parent would therefore capture it and
 * render D-074's PHP envelope where its clients expect
 * {@code ApiErrorBody}, silently changing a Wave 12.1 contract. Naming only
 * the subpackage keeps the two surfaces apart.
 */
@RestControllerAdvice(basePackages = {
	"com.workin.legacy.employees",
	"com.workin.legacy.workforce",
	"com.workin.legacy.attendance.records",
})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LegacyWireExceptionHandler {

	private static final Logger LOG = LoggerFactory.getLogger(LegacyWireExceptionHandler.class);

	/**
	 * D-084's fixed text. Deliberately not a catalog key and deliberately not
	 * localized: it is Phase 1's own contract for a failure legacy never
	 * defined, not a legacy message.
	 */
	private static final String INTERNAL_SERVER_ERROR = "Internal server error";

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

	/**
	 * D-084: the final fallback for an exception no specific handler claimed.
	 *
	 * <p>Legacy has no contract here. {@code employee_cascade_delete_related()}
	 * rolls back and rethrows, {@code delete.php} does not translate it, and
	 * what the client then sees depends on {@code AppConfig::DEBUG} -- a value
	 * that lives in the gitignored {@code constants.php} and cannot be
	 * established from this repository. With it true PHP emits the exception
	 * message, file, line and stack trace; with it false the response depends on
	 * the runtime's {@code display_errors} and is not a stable JSON contract at
	 * all.
	 *
	 * <p>So Phase 1 takes an explicit divergence: one deterministic body,
	 * carrying nothing about the failure. No {@code data}, no {@code meta}, no
	 * exception text, no SQL, no file, line or stack. The real exception is
	 * logged here instead, which is where that detail belongs.
	 *
	 * <p>{@code Exception}, not {@code Throwable}: an {@code OutOfMemoryError} or
	 * {@code StackOverflowError} must not be rendered as a tidy 500 and left to
	 * continue. Transaction helpers still catch {@code Throwable} where PHP's
	 * rollback semantics require it -- rolling back and rendering are different
	 * jobs.
	 *
	 * <p>This fallback covers exactly the packages this advice lists -- today
	 * {@code com.workin.legacy.employees} and {@code com.workin.legacy.workforce}
	 * -- and D-084 authorizes a later legacy-route wave to inherit it by adding
	 * its package to that list rather than by defining a second envelope.
	 * {@code /api/legacy/**} and the PostgreSQL surface remain untouched.
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<LegacyApiResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
		LOG.error("unhandled exception serving {} {}", request.getMethod(), request.getRequestURI(), ex);
		return ResponseEntity.status(500).body(LegacyApiResponse.fail(INTERNAL_SERVER_ERROR, null));
	}

}
