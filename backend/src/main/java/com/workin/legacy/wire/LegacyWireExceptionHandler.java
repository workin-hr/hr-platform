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
import com.workin.legacy.organization.LegacyBranchController;

/**
 * Renders the PHP envelope for controllers that serve legacy's literal
 * {@code /apis/api/**} routes (D-074), without changing unrelated platform
 * controllers.
 *
 * <p>The dedicated {@code organization.php} and {@code auth.php} subpackages
 * are Wave 12.R adapters. Keeping them separate lets the already-merged REST
 * controllers retain their old error contract until their aliases are retired,
 * while the public PHP routes use this handler immediately.
 */
@RestControllerAdvice(
		basePackages = {
			"com.workin.legacy.employees",
			"com.workin.legacy.workforce",
			"com.workin.legacy.attendance",
			"com.workin.legacy.attendance.records",
			"com.workin.legacy.schedules",
			"com.workin.legacy.payroll",
			"com.workin.legacy.companies",
			"com.workin.legacy.configs",
			"com.workin.legacy.reference",
			"com.workin.legacy.dashboard",
			"com.workin.legacy.settings",
			"com.workin.legacy.records",
			"com.workin.legacy.planning",
			"com.workin.legacy.people",
			"com.workin.legacy.notifications",
			"com.workin.legacy.profile",
			"com.workin.legacy.organization.php",
			"com.workin.legacy.auth.php",
		},
		assignableTypes = {
			LegacyBranchController.class,
		})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LegacyWireExceptionHandler {

	private static final Logger LOG = LoggerFactory.getLogger(LegacyWireExceptionHandler.class);
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
	 * Shared legacy guards predate the D-074 envelope and raise
	 * {@link ApiException}. The key/status are already the PHP ones; only the
	 * response shape changes here.
	 */
	@ExceptionHandler(ApiException.class)
	public ResponseEntity<LegacyApiResponse> handlePlatform(ApiException ex, HttpServletRequest request) {
		String text = messages.translate(messages.resolveLocale(request), ex.getCode(), null);
		return ResponseEntity.status(ex.getStatus()).body(LegacyApiResponse.fail(text, null));
	}

	/** D-084 deterministic fallback for an unexpected legacy-route failure. */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<LegacyApiResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
		LOG.error("unhandled exception serving {} {}", request.getMethod(), request.getRequestURI(), ex);
		return ResponseEntity.status(500).body(LegacyApiResponse.fail(INTERNAL_SERVER_ERROR, null));
	}

}
