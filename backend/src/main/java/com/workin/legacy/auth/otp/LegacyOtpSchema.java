package com.workin.legacy.auth.otp;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * {@code otp_table_has_column()} and {@code otp_request_logs_ready()}
 * ({@code helpers/otp_helper.php:11-37}).
 *
 * <h2>Why the OTP helper probes its own schema at all</h2>
 * <p>{@code otp_codes} in {@code hr-legacy@d113204} has five columns --
 * {@code phone}, {@code code}, {@code is_used}, {@code expires_at},
 * {@code created_at} -- and <b>no</b> {@code ip_address}, {@code user_agent}
 * or {@code purpose}. {@code otp_request_logs} does not exist at all. Every
 * one of these probes therefore answers <b>false</b> against the frozen
 * schema, and the helper takes its degraded path on every call.
 *
 * <p>That is not a hypothetical branch to be optimised away: it is the only
 * branch that runs. The probes are ported anyway, because the PHP is written
 * to work either way and a port that hard-coded "false" would be wrong the day
 * someone adds the columns -- which is a live possibility, since the degraded
 * path has the consequence described on {@link LegacyOtpRateLimit}.
 *
 * <h2>One difference from PHP, stated rather than hidden</h2>
 * <p>PHP caches each answer in a {@code static} that lives for the request.
 * This cache lives for the application. A column added to a running database
 * is picked up by PHP on the next request and by this service only after a
 * restart. Schema changes here arrive with a deployment, so that is
 * acceptable -- but it is a difference, not an equivalence.
 */
@Service
public class LegacyOtpSchema {

	private final JdbcTemplate jdbcTemplate;
	private final Map<String, Boolean> columnCache = new ConcurrentHashMap<>();
	private volatile Boolean requestLogsReady;

	public LegacyOtpSchema(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/** {@code otp_table_has_column($column)}. */
	public boolean otpCodesHasColumn(String column) {
		return columnCache.computeIfAbsent(column, name -> {
			Long found = jdbcTemplate.queryForObject("""
					SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
					WHERE TABLE_SCHEMA = DATABASE()
					  AND TABLE_NAME = ?
					  AND COLUMN_NAME = ?""",
					Long.class, "otp_codes", name);
			return found != null && found > 0;
		});
	}

	/** {@code otp_request_logs_ready()}. */
	public boolean requestLogsReady() {
		Boolean cached = requestLogsReady;
		if (cached != null) {
			return cached;
		}
		Long found = jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
				WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?""",
				Long.class, "otp_request_logs");
		boolean ready = found != null && found > 0;
		requestLogsReady = ready;
		return ready;
	}
}
