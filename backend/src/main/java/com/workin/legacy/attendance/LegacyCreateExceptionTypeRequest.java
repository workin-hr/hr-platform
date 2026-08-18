package com.workin.legacy.attendance;

/** {@code create.php}'s body: {@code name} required (trimmed/validated in the service, not here -- legacy's own 400 is a trimmed-empty check, not a bean-validation shape). {@code isActive} defaults to {@code true} when absent, matching {@code (int) ($body[IS_ACTIVE] ?? 1)}. */
public record LegacyCreateExceptionTypeRequest(String name, Boolean isActive) {
}
