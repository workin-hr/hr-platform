package com.workin.legacy.auth.otp;

import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.workin.legacy.wire.LegacyApiException;

/**
 * {@code otp_count_recent_sends()} and {@code otp_assert_can_send()}
 * ({@code helpers/otp_helper.php:105-172}) -- legacy's whole defence against
 * OTP bombing.
 *
 * <h2>What the three limits are meant to be</h2>
 * <ol>
 * <li>a per-phone cooldown across every purpose: 90 seconds for
 *     {@code password_reset}, 60 otherwise;</li>
 * <li>a per-phone hourly cap for <em>this</em> purpose: 5 for
 *     {@code password_reset}, 10 otherwise;</li>
 * <li>a per-IP hourly cap of 20.</li>
 * </ol>
 *
 * <h2>What two of them actually are, against the frozen schema -- R-014</h2>
 * <p>{@code otp_count_recent_sends()} drops any predicate whose column does not
 * exist. {@code otp_codes} has no {@code purpose} and no {@code ip_address},
 * and {@code otp_request_logs} does not exist, so:
 *
 * <ul>
 * <li>the <b>per-purpose</b> cap degrades to a per-phone cap across all
 *     purposes -- stricter than intended, and harmless;</li>
 * <li>the <b>per-IP</b> cap degrades to something else entirely. Called as
 *     {@code otp_count_recent_sends(null, $ip, '', 3600)} it has no phone
 *     predicate, no usable IP predicate and no purpose predicate, so what
 *     survives is {@code SELECT COUNT(*) FROM otp_codes WHERE created_at >
 *     NOW() - INTERVAL 3600 SECOND} -- <b>every OTP the platform issued in the
 *     last hour, for every phone</b>. At twenty, it answers 429 to
 *     <em>everyone</em>.</li>
 * </ul>
 *
 * <p>So the frozen system can issue at most twenty OTPs per hour in total, and
 * the twenty-first user to register, reset a password or verify a phone is
 * told to wait -- with no way to tell that from a real per-IP block. This is
 * reproduced exactly, and recorded as <b>R-014</b> rather than repaired: it is
 * a legacy defect with a real capacity consequence, and fixing it in Java
 * alone would make the two systems disagree about who is rate-limited.
 *
 * <p>The guard is only reached when {@code $ip !== ''}, so a request with no
 * resolvable client IP skips the global cap and is limited by the per-phone
 * rules alone.
 */
@Service
public class LegacyOtpRateLimit {

	private final JdbcTemplate jdbcTemplate;
	private final LegacyOtpSchema schema;

	public LegacyOtpRateLimit(DataSource legacyDataSource, LegacyOtpSchema schema) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
		this.schema = schema;
	}

	/**
	 * {@code otp_count_recent_sends($phone, $ip, $purpose, $withinSeconds)}.
	 *
	 * <p>A null or empty argument contributes no predicate, and so does an
	 * argument whose backing column is absent. That is how a call meant to
	 * count one IP's sends ends up counting the whole table.
	 */
	public long countRecentSends(String phone, String ip, String purpose, long withinSeconds) {
		if (schema.requestLogsReady()) {
			List<Object> binds = new ArrayList<>();
			StringBuilder where = new StringBuilder("created_at > DATE_SUB(NOW(), INTERVAL ? SECOND)");
			binds.add(withinSeconds);
			if (purpose != null && !purpose.isEmpty()) {
				where.append(" AND purpose = ?");
				binds.add(purpose);
			}
			if (phone != null && !phone.isEmpty()) {
				where.append(" AND phone = ?");
				binds.add(phone);
			}
			if (ip != null && !ip.isEmpty()) {
				where.append(" AND ip_address = ?");
				binds.add(ip);
			}
			return count("SELECT COUNT(*) FROM otp_request_logs WHERE " + where, binds);
		}

		List<Object> binds = new ArrayList<>();
		StringBuilder where = new StringBuilder("created_at > DATE_SUB(NOW(), INTERVAL ? SECOND)");
		binds.add(withinSeconds);
		if (phone != null && !phone.isEmpty()) {
			where.append(" AND phone = ?");
			binds.add(phone);
		}
		if (ip != null && !ip.isEmpty() && schema.otpCodesHasColumn("ip_address")) {
			where.append(" AND ip_address = ?");
			binds.add(ip);
		}
		if (purpose != null && !purpose.isEmpty() && schema.otpCodesHasColumn("purpose")) {
			where.append(" AND purpose = ?");
			binds.add(purpose);
		}
		return count("SELECT COUNT(*) FROM otp_codes WHERE " + where, binds);
	}

	/**
	 * {@code otp_assert_can_send($phone, $purpose)} -- the three checks in
	 * PHP's order, so the <em>first</em> one that trips is the one the caller
	 * sees. The cooldown answers {@code please_wait_before_resending} and both
	 * caps answer {@code otp_too_many_requests}; all three are 429.
	 *
	 * @param phone already normalised to digits by the caller
	 * @param ip the resolved client IP, or {@code ""} when none could be read
	 */
	public void assertCanSend(String phone, String purpose, String ip) {
		long cooldownSeconds = "password_reset".equals(purpose) ? 90 : 60;
		if (!phone.isEmpty() && countRecentSends(phone, null, "", cooldownSeconds) > 0) {
			throw new LegacyApiException(429, "please_wait_before_resending");
		}

		long phoneHourlyMax = "password_reset".equals(purpose) ? 5 : 10;
		if (!phone.isEmpty() && countRecentSends(phone, null, purpose, 3600) >= phoneHourlyMax) {
			throw new LegacyApiException(429, "otp_too_many_requests");
		}

		if (ip != null && !ip.isEmpty() && countRecentSends(null, ip, "", 3600) >= 20) {
			throw new LegacyApiException(429, "otp_too_many_requests");
		}
	}

	private long count(String sql, List<Object> binds) {
		Long value = jdbcTemplate.queryForObject(sql, Long.class, binds.toArray());
		return value == null ? 0L : value;
	}
}
