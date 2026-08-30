package com.workin.legacy.auth.otp;

import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The {@code otp_codes} reads and writes behind {@code helpers/otp_helper.php}. */
@Repository
public class LegacyOtpStore {

	private final JdbcTemplate jdbcTemplate;
	private final LegacyOtpSchema schema;

	public LegacyOtpStore(DataSource legacyDataSource, LegacyOtpSchema schema) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
		this.schema = schema;
	}

	/**
	 * {@code otp_clear_for_phone()} ({@code otp_helper.php:72-86}).
	 *
	 * <p>A <b>soft</b> invalidate: it sets {@code is_used = 1} rather than
	 * deleting, deliberately, so the history survives for audit. That choice is
	 * why {@link LegacyOtpRateLimit}'s fallback counting works at all -- and
	 * also why R-014's global count keeps growing.
	 *
	 * <p>{@code COALESCE(is_used, 0) = 0} rather than {@code is_used = 0}: the
	 * column is {@code NOT NULL} in the frozen schema, so the COALESCE is
	 * defensive, but it is preserved because a nullable column in some
	 * deployment would change which rows are cleared.
	 */
	public void clearForPhone(String normalizedPhone) {
		if (normalizedPhone.isEmpty()) {
			return;
		}
		jdbcTemplate.update(
				"UPDATE otp_codes SET is_used = 1 WHERE phone = ? AND COALESCE(is_used, 0) = 0",
				normalizedPhone);
	}

	/**
	 * The INSERT from {@code otp_issue_for_phone()}, whose column list is built
	 * from whichever optional columns this deployment actually has. Against the
	 * frozen schema that is none of them, so the statement is
	 * {@code (phone, code, expires_at)}.
	 *
	 * <p>{@code expires_at} is computed by the <b>database</b>
	 * ({@code DATE_ADD(NOW(), INTERVAL ? MINUTE)}), not by the application, so
	 * expiry is measured against the database clock and no JVM/DB timezone skew
	 * can shorten or extend an OTP's life.
	 */
	public void insert(String normalizedPhone, String code, int expiresMinutes,
			String ip, String userAgent, String purpose) {
		List<String> columns = new ArrayList<>(List.of("phone", "code", "expires_at"));
		List<String> placeholders = new ArrayList<>(List.of("?", "?", "DATE_ADD(NOW(), INTERVAL ? MINUTE)"));
		List<Object> binds = new ArrayList<>(List.of(normalizedPhone, code, expiresMinutes));

		if (schema.otpCodesHasColumn("ip_address")) {
			columns.add("ip_address");
			placeholders.add("?");
			binds.add(ip.isEmpty() ? null : ip);
		}
		if (schema.otpCodesHasColumn("user_agent")) {
			columns.add("user_agent");
			placeholders.add("?");
			binds.add(userAgent.isEmpty() ? null : userAgent);
		}
		if (schema.otpCodesHasColumn("purpose")) {
			columns.add("purpose");
			placeholders.add("?");
			binds.add(purpose);
		}

		jdbcTemplate.update(
				"INSERT INTO otp_codes (" + String.join(", ", columns) + ") VALUES ("
						+ String.join(", ", placeholders) + ")",
				binds.toArray());
	}

	/** {@code otp_log_request()} -- a no-op unless {@code otp_request_logs} exists. */
	public void logRequest(String normalizedPhone, String purpose, String ip, String userAgent) {
		if (!schema.requestLogsReady()) {
			return;
		}
		jdbcTemplate.update(
				"INSERT INTO otp_request_logs (phone, purpose, ip_address, user_agent) VALUES (?, ?, ?, ?)",
				normalizedPhone, purpose, ip.isEmpty() ? null : ip, userAgent.isEmpty() ? null : userAgent);
	}

	/**
	 * {@code otp_verify_latest_for_phone()} ({@code otp_helper.php:239-261}).
	 *
	 * <p>Four conditions, all of them necessary: the phone, the code,
	 * {@code expires_at > NOW()} and not already used. The ordering picks the
	 * newest row, but note it does <b>not</b> restrict to the newest row before
	 * matching the code -- an older, still-unexpired, still-unused code for the
	 * same phone would also verify. In practice {@code otp_issue_for_phone()}
	 * clears the previous rows first, so at most one is ever active.
	 *
	 * @return the matching row's id, or null
	 */
	public Long verifyLatest(String normalizedPhone, String trimmedCode) {
		if (normalizedPhone.isEmpty() || trimmedCode.isEmpty()) {
			return null;
		}
		List<Long> ids = jdbcTemplate.queryForList("""
				SELECT id FROM otp_codes
				WHERE phone = ? AND code = ?
				  AND expires_at > NOW()
				  AND COALESCE(is_used, 0) = 0
				ORDER BY created_at DESC, id DESC
				LIMIT 1""", Long.class, normalizedPhone, trimmedCode);
		return ids.isEmpty() ? null : ids.get(0);
	}
}
