package com.workin.legacy.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.workin.legacy.phone.LegacyPhoneNumbers;
import com.workin.legacy.wire.LegacyApiException;

/**
 * A miss budget for the app's three password logins -- {@code login_employee},
 * {@code login_company} and {@code login_desktop} -- which PHP runs with no
 * attempt limit at all (D-289).
 *
 * <p>The pattern is {@code PlatformAdminLoginThrottle}'s, and so is the table:
 * rows in {@code platform_admin_login_attempts}, keyed by a SHA-256 of a
 * namespaced identifier ({@code api-phone:} or {@code api-addr:}, never the
 * dashboard's {@code web:}), so no schema change is needed and the same
 * scheduled purge ages them out. Unlike that class it writes through plain
 * autocommit statements: a mobile login is latency-sensitive, and each
 * {@code REQUIRES_NEW} transaction would cost two extra round trips.
 *
 * <p>Two budgets, both charged <em>before</em> the password is checked, so a
 * burst of parallel guesses cannot all see an unspent budget:
 * <ul>
 * <li><b>the phone</b>, normalised to its digits: {@value #MAX_PHONE_MISSES}
 *     misses per window, legacy's dashboard floor. One phone is one budget
 *     across all three routes, so a guesser cannot rotate between them.</li>
 * <li><b>the client address</b>, as the servlet container resolves it --
 *     {@code getRemoteAddr()}, which honours {@code X-Forwarded-For} only from
 *     the proxies {@code server.forward-headers-strategy} trusts, never the
 *     header ladder {@code LegacyClientAddress} copies from PHP. Wider,
 *     {@value #MAX_ADDRESS_MISSES}, because a mobile carrier puts many
 *     customers behind one address, and it bounds one guesser spraying many
 *     phones.</li>
 * </ul>
 *
 * <p>Only a 401 is a miss -- an unknown phone or a wrong password, the two
 * answers a guesser gets. Any other outcome means the password was right, so
 * both reservations are returned; a success also clears the phone's budget, as
 * the dashboard's does. The address budget is never cleared by a success:
 * otherwise one valid account would reset it for every guess made from there.
 */
@Service
public class LegacyLoginThrottle {

	public static final int MAX_PHONE_MISSES = 8;

	public static final int MAX_ADDRESS_MISSES = 60;

	public static final Duration WINDOW = Duration.ofMinutes(15);

	private final JdbcTemplate jdbcTemplate;
	private final Clock clock;

	@Autowired
	public LegacyLoginThrottle(DataSource legacyDataSource) {
		this(legacyDataSource, Clock.systemUTC());
	}

	LegacyLoginThrottle(DataSource legacyDataSource, Clock clock) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
		this.clock = clock;
	}

	/**
	 * Runs {@code login} inside both budgets.
	 *
	 * @throws LegacyApiException 429 {@code too_many_login_attempts} when either
	 *         budget is spent, before the password is looked at
	 */
	public <T> T guard(Object rawPhone, String clientAddress, Supplier<T> login) {
		String phone = hash("api-phone:" + LegacyPhoneNumbers.digitsOnly(
				rawPhone == null ? "" : String.valueOf(rawPhone)));
		String address = hash("api-addr:" + (clientAddress == null ? "" : clientAddress));

		Instant now = this.clock.instant();
		// UTC wall time, which is how the dashboard throttle's Instant is stored
		// and what its purge compares against.
		LocalDateTime at = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
		this.jdbcTemplate.update(
				"INSERT INTO platform_admin_login_attempts (identifier_hash, attempted_at) VALUES (?, ?), (?, ?)",
				phone, at, address, at);

		Map<String, Object> counts = this.jdbcTemplate.queryForMap(
				"SELECT COALESCE(SUM(identifier_hash = ?), 0) AS phone_rows,"
						+ " COALESCE(SUM(identifier_hash = ?), 0) AS address_rows"
						+ " FROM platform_admin_login_attempts"
						+ " WHERE identifier_hash IN (?, ?) AND attempted_at > ?",
				phone, address, phone, address, LocalDateTime.ofInstant(now.minus(WINDOW), ZoneOffset.UTC));
		if (((Number) counts.get("phone_rows")).longValue() > MAX_PHONE_MISSES
				|| ((Number) counts.get("address_rows")).longValue() > MAX_ADDRESS_MISSES) {
			releaseOne(phone);
			releaseOne(address);
			throw new LegacyApiException(429, "too_many_login_attempts");
		}

		T result;
		try {
			result = login.get();
		} catch (LegacyApiException ex) {
			if (ex.getStatus() != 401) {
				releaseOne(phone);
				releaseOne(address);
			}
			throw ex;
		} catch (RuntimeException | Error ex) {
			releaseOne(phone);
			releaseOne(address);
			throw ex;
		}
		this.jdbcTemplate.update("DELETE FROM platform_admin_login_attempts WHERE identifier_hash = ?", phone);
		releaseOne(address);
		return result;
	}

	/** One row of this key, not a particular one: the budget only ever counts them. */
	private void releaseOne(String identifierHash) {
		this.jdbcTemplate.update(
				"DELETE FROM platform_admin_login_attempts WHERE identifier_hash = ? ORDER BY id DESC LIMIT 1",
				identifierHash);
	}

	/** The same digest {@code PlatformAdminLoginThrottle} stores: the caller chooses the identifier. */
	private static String hash(String identifier) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(identifier.strip().toLowerCase().getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}
}
