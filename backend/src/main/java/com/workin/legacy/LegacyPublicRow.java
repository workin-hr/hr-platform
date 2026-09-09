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
 * {@link com.workin.legacy.employees.LegacyEmployeeStore} strips the same keys
 * inside its own row mapper, which protects the queries that go through it --
 * and silently protects nothing when a different module runs its own
 * {@code SELECT * FROM employees}. Wave 13.4c's join-request accept/reject did
 * exactly that and returned both columns.
 *
 * <p><b>{@link #SENSITIVE_KEYS} is the one list.</b> There were four copies of
 * it, and when {@code 505004f} added {@code ip} to
 * {@code sensitive_response_keys()} all four kept the old pair -- so the port
 * served a column PHP had just classified as a secret. Nine tests asserted the
 * stale pair by name and agreed with every copy. A list duplicated four ways
 * cannot be kept honest by review, so the copies now read this one.
 */
public final class LegacyPublicRow {

	/**
	 * {@code sensitive_response_keys()} ({@code helpers/public_row.php:10}).
	 *
	 * <p>{@code ip} is the last-login address {@code auth_save_last_ip()}
	 * records. It is a column on both {@code employees} and {@code companies},
	 * so an unrestricted {@code SELECT *} carries it to the wire unless it is
	 * removed here.
	 */
	public static final List<String> SENSITIVE_KEYS = List.of("password_hash", "token_version", "ip");

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
