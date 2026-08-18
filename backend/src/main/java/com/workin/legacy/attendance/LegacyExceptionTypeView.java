package com.workin.legacy.attendance;

import java.time.Instant;

/**
 * The legacy {@code public_row($exception_type)} shape: every column,
 * verbatim field names, minus nothing (this table carries no sensitive
 * columns to strip, unlike {@code employees}).
 */
public record LegacyExceptionTypeView(
		Long id, Long companyId, String name, boolean isActive, Instant createdAt, Instant updatedAt) {

	static LegacyExceptionTypeView of(LegacyExceptionType exceptionType) {
		return new LegacyExceptionTypeView(
				exceptionType.getId(),
				exceptionType.getCompanyId(),
				exceptionType.getName(),
				exceptionType.active(),
				exceptionType.getCreatedAt(),
				exceptionType.getUpdatedAt());
	}

}
