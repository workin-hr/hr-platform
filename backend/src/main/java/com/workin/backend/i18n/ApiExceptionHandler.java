package com.workin.backend.i18n;

import java.util.List;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import com.workin.backend.tenancy.TenantContextException;

/**
 * The single error renderer for the {code, message} contract. Also
 * owns the uniform-404 duty formerly in TenantContextExceptionHandler
 * (docs/architecture/authorization-model.md §8): every
 * TenantContextException — membership not found, disabled, or another
 * identity's — renders the same error.not_found body as any missing
 * resource, never a distinguishable 403-vs-404 signal.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	private final Messages messages;
	private final MessageSource messageSource;

	public ApiExceptionHandler(Messages messages, MessageSource messageSource) {
		this.messages = messages;
		this.messageSource = messageSource;
	}

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ApiErrorBody> handleApi(ApiException ex) {
		return ResponseEntity.status(ex.getStatus())
				.body(new ApiErrorBody(ex.getCode(), messages.get(ex.getCode(), ex.getArgs())));
	}

	/**
	 * Bare status-only throws (the uniform-404/403 pattern keeps its
	 * call sites) render status-derived generic codes. A lingering
	 * reason string would pass through as the message verbatim —
	 * Task 3 migrates every such site to ApiException, so this branch
	 * is a safety net, not a path new code should take.
	 */
	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ApiErrorBody> handleStatus(ResponseStatusException ex) {
		String code = genericCode(HttpStatus.resolve(ex.getStatusCode().value()));
		String message = ex.getReason() != null ? ex.getReason() : messages.get(code);
		return ResponseEntity.status(ex.getStatusCode()).body(new ApiErrorBody(code, message));
	}

	@ExceptionHandler(TenantContextException.class)
	public ResponseEntity<ApiErrorBody> handleTenantContext(TenantContextException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ApiErrorBody(MessageKeys.ERROR_NOT_FOUND, messages.get(MessageKeys.ERROR_NOT_FOUND)));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiValidationErrorBody> handleValidation(MethodArgumentNotValidException ex) {
		Locale locale = LocaleContextHolder.getLocale();
		List<FieldViolation> fields = ex.getBindingResult().getFieldErrors().stream()
				.map(fieldError -> new FieldViolation(
						fieldError.getField(),
						// FieldError is a MessageSourceResolvable: its codes
						// (NotBlank.upsertShiftRequest.name, ..., NotBlank)
						// resolve against the catalogs, falling back to the
						// constraint's own default text.
						messageSource.getMessage(fieldError, locale)))
				.toList();
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ApiValidationErrorBody(
						MessageKeys.ERROR_VALIDATION, messages.get(MessageKeys.ERROR_VALIDATION), fields));
	}

	private static String genericCode(HttpStatus status) {
		if (status == null) {
			return MessageKeys.ERROR_BAD_REQUEST;
		}
		return switch (status) {
			case NOT_FOUND -> MessageKeys.ERROR_NOT_FOUND;
			case FORBIDDEN -> MessageKeys.ERROR_FORBIDDEN;
			case UNAUTHORIZED -> MessageKeys.ERROR_UNAUTHORIZED;
			case CONFLICT -> MessageKeys.ERROR_CONFLICT;
			// Any other status a bare throw carries maps to the
			// catch-all; specific statuses get keyed ApiExceptions.
			default -> MessageKeys.ERROR_BAD_REQUEST;
		};
	}

}
