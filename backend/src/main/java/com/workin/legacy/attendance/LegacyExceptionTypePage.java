package com.workin.legacy.attendance;

import java.util.List;

/**
 * {@code list.php}'s {@code pagination_meta()} companion, field-for-field
 * (page/limit/total/totalPages/hasNext/hasPrevious), carried as one
 * response body rather than legacy's separate {@code {message,data,meta}}
 * envelope -- Phase 1's own established response shape ({@link
 * com.workin.legacy.auth.LegacyAuthResponse}, {@link
 * com.workin.backend.i18n.ApiErrorBody}) is already a flat record, not a
 * PHP-envelope reproduction, and this follows that precedent rather than
 * introducing a second one.
 */
public record LegacyExceptionTypePage(
		List<LegacyExceptionTypeView> data,
		int page,
		int limit,
		long total,
		int totalPages,
		boolean hasNext,
		boolean hasPrevious) {
}
