package com.workin.legacy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code public_row()} / {@code public_rows()}
 * ({@code hr-legacy/apis/helpers/public_row.php}).
 *
 * <p>"The full model minus secrets": every column of the row except
 * {@code password_hash} and {@code token_version}, which
 * {@code sensitive_response_keys()} names. PHP applies it to <b>every</b>
 * employee row that reaches a response, and it is the only thing standing
 * between a {@code SELECT *} over {@code employees} and a password hash on the
 * wire.
 *
 * <p>Extracted here because that makes it reusable at the point of return.
 * {@link com.workin.legacy.employees.LegacyEmployeeStore} strips the same two
 * keys inside its own row mapper, which protects the queries that go through
 * it -- and silently protects nothing when a different module runs its own
 * {@code SELECT * FROM employees}. Wave 13.4c's join-request accept/reject did
 * exactly that and returned both columns.
 */
public final class LegacyPublicRow {

	/** {@code sensitive_response_keys()}. */
	private static final List<String> SENSITIVE_KEYS = List.of("password_hash", "token_version");

	private LegacyPublicRow() {
	}

	/**
	 * A copy of {@code row} without the sensitive keys, preserving key order.
	 *
	 * <p>Null in, null out: PHP's callers write
	 * {@code public_row($updated ?? $row)} and {@code $row ? public_row($row) : null},
	 * so a missing row stays missing rather than becoming an empty object.
	 */
	public static Map<String, Object> of(Map<String, Object> row) {
		if (row == null) {
			return null;
		}
		Map<String, Object> out = new LinkedHashMap<>(row);
		SENSITIVE_KEYS.forEach(out::remove);
		return out;
	}
}
