package com.workin.legacy.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.workin.legacy.phone.LegacyPhoneNumbers;
import com.workin.legacy.phone.PhoneLookup;
import com.workin.legacy.wire.LegacyApiException;

/**
 * A miss budget for the app's three password logins -- {@code login_employee},
 * {@code login_company} and {@code login_desktop} -- which PHP runs with no
 * attempt limit at all (D-289).
 *
 * <p>The pattern is {@code PlatformAdminLoginThrottle}'s, and so is the table:
 * rows in {@code platform_admin_login_attempts}, keyed by a SHA-256 of a
 * namespaced identifier ({@code api-pair:}, {@code api-phone:} or
 * {@code api-addr:}, never the dashboard's {@code web:}), so no schema change
 * is needed and the same scheduled purge ages them out. Unlike that class it
 * writes through plain autocommit statements: a mobile login is
 * latency-sensitive, and each {@code REQUIRES_NEW} transaction would cost two
 * extra round trips.
 *
 * <p>Three budgets, all charged <em>before</em> the password is checked, so a
 * burst of parallel guesses cannot all see an unspent budget:
 * <ul>
 * <li><b>the phone from one address</b>: {@value #MAX_PAIR_MISSES} misses per
 *     window, legacy's dashboard floor. This is the one a guesser meets.
 *     Keyed on the pair rather than the phone alone so that spending it does
 *     not lock the phone's owner out: they sign in from their own address
 *     with a budget of their own.</li>
 * <li><b>the phone from anywhere</b>: {@value #MAX_PHONE_MISSES}, a ceiling
 *     for a guesser who rotates addresses. Reaching it does lock the owner out
 *     until the window passes -- the price of having a ceiling at all, and
 *     five addresses' worth of the strict budget to pay it.</li>
 * <li><b>the client address</b>, as the servlet container resolves it --
 *     {@code getRemoteAddr()}, which honours {@code X-Forwarded-For} only from
 *     the proxies {@code server.forward-headers-strategy} trusts, never the
 *     header ladder {@code LegacyClientAddress} copies from PHP. Wider,
 *     {@value #MAX_ADDRESS_MISSES}, because a mobile carrier puts many
 *     customers behind one address, and it bounds one guesser spraying many
 *     phones.</li>
 * </ul>
 * One phone is one budget across all three routes, so a guesser cannot
 * rotate between them.
 *
 * <h2>The key is the number, and the lookup can reach nothing else</h2>
 * <p>A phone is keyed on its canonical E.164 number (ADR-0020, D-291), so
 * {@code 01012345678}, {@code +20 10 1234 5678}, {@code 0020 1012345678} and
 * the same digits in Arabic-Indic are one budget, not four. The route never
 * binds what the client sent: it is handed the {@link PhoneLookup} the key was
 * made from, which binds only the stored spellings of those numbers and keeps
 * only rows that canonicalise to them. A number typed nationally with no
 * country can read as more than one number -- {@code 0501234567} is a Saudi
 * mobile and an Egyptian landline -- and then every reading is charged, so
 * whichever account the lookup reaches was charged under its own number.
 *
 * <p>Input that is not a valid phone number -- letters, circled or dingbat
 * digits, an extension, a number the metadata rejects -- is refused before
 * any lookup, answered as the route answers an unknown phone, and charged to
 * the address alone, never to one bucket every such input would share. The
 * collation no longer matters here: MariaDB's {@code utf8mb4_unicode_ci}
 * equates circled and other non-decimal digits to ASCII ones, and that was
 * why {@code bindablePhone} kept an allowlist of what could reach the
 * {@code WHERE}; nothing the client typed reaches it now.
 *
 * <p>Only a 401 is a miss -- an unknown phone or a wrong password, the two
 * answers a guesser gets. Every other outcome, a success included, returns
 * this attempt's own reservations and nothing else. A success does not clear
 * the phone's earlier misses: they may have been another account's --
 * {@code join_company} lets a pending row share the owner's phone -- and a
 * login that succeeded proves only its own password.
 */
@Service
public class LegacyLoginThrottle {

	private static final Logger log = LoggerFactory.getLogger(LegacyLoginThrottle.class);

	public static final int MAX_PAIR_MISSES = 8;

	public static final int MAX_PHONE_MISSES = 40;

	public static final int MAX_ADDRESS_MISSES = 60;

	public static final Duration WINDOW = Duration.ofMinutes(15);

	private final JdbcTemplate jdbcTemplate;
	private final LegacyPhoneNumbers phoneNumbers;
	private final Clock clock;

	@Autowired
	public LegacyLoginThrottle(DataSource legacyDataSource, LegacyPhoneNumbers phoneNumbers) {
		this(legacyDataSource, phoneNumbers, Clock.systemUTC());
	}

	LegacyLoginThrottle(DataSource legacyDataSource, LegacyPhoneNumbers phoneNumbers, Clock clock) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
		this.phoneNumbers = phoneNumbers;
		this.clock = clock;
	}

	/**
	 * Runs {@code login} inside every budget.
	 *
	 * @param rawPhone the request's {@code phone}, before any conversion
	 * @param unknownPhone the route's answer to a phone that matches no
	 *        account, given to input that is not a phone number, so the
	 *        refusal reads exactly as a miss does
	 * @param login the login, given the lookup to find its rows with in place
	 *        of the request's phone
	 * @throws LegacyApiException 429 {@code too_many_login_attempts} when any
	 *         budget is spent, before the password is looked at
	 */
	public <T> T guard(Object rawPhone, String clientAddress,
			Supplier<LegacyApiException> unknownPhone, Function<PhoneLookup, T> login) {
		String address = clientAddress == null ? "" : clientAddress;
		PhoneLookup lookup = this.phoneNumbers.lookup(rawPhone);

		Map<String, Integer> budgets = new LinkedHashMap<>();
		budgets.put(hash("api-addr:" + address), MAX_ADDRESS_MISSES);
		for (String e164 : lookup.e164s()) {
			budgets.put(hash("api-phone:" + e164), MAX_PHONE_MISSES);
			budgets.put(hash("api-pair:" + e164 + "|" + address), MAX_PAIR_MISSES);
		}

		Instant now = this.clock.instant();
		List<Long> reservation = reserve(budgets.keySet(), now);
		if (!withinBudgets(budgets, now)) {
			release(reservation);
			throw new LegacyApiException(429, "too_many_login_attempts");
		}

		if (lookup.isEmpty()) {
			// Refused before any lookup, and kept as a miss against the address.
			throw unknownPhone.get();
		}

		T result;
		try {
			result = login.apply(lookup);
		} catch (LegacyApiException ex) {
			if (ex.getStatus() != 401) {
				release(reservation);
			}
			throw ex;
		} catch (RuntimeException | Error ex) {
			release(reservation);
			throw ex;
		}
		release(reservation);
		return result;
	}

	/**
	 * One row per budget, committed before anything is counted, so the k-th
	 * attempt to proceed counts at least k rows. The ids come back so that
	 * exactly these rows, and no one else's, can be returned.
	 */
	private List<Long> reserve(Iterable<String> keys, Instant now) {
		// UTC wall time, which is how the dashboard throttle's Instant is stored
		// and what its purge compares against.
		LocalDateTime at = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
		List<String> values = new ArrayList<>();
		List<Object> args = new ArrayList<>();
		for (String key : keys) {
			values.add("(?, ?)");
			args.add(key);
			args.add(at);
		}
		return this.jdbcTemplate.queryForList(
				"INSERT INTO platform_admin_login_attempts (identifier_hash, attempted_at) VALUES "
						+ String.join(", ", values) + " RETURNING id",
				Long.class, args.toArray());
	}

	private boolean withinBudgets(Map<String, Integer> budgets, Instant now) {
		List<Object> args = new ArrayList<>(budgets.keySet());
		args.add(LocalDateTime.ofInstant(now.minus(WINDOW), ZoneOffset.UTC));
		Map<String, Long> counts = new LinkedHashMap<>();
		this.jdbcTemplate.query(
				"SELECT identifier_hash, COUNT(*) AS attempts FROM platform_admin_login_attempts"
						+ " WHERE identifier_hash IN (" + String.join(", ", Collections.nCopies(budgets.size(), "?"))
						+ ") AND attempted_at > ? GROUP BY identifier_hash",
				row -> {
					counts.put(row.getString("identifier_hash"), row.getLong("attempts"));
				},
				args.toArray());
		return budgets.entrySet().stream()
				.allMatch(budget -> counts.getOrDefault(budget.getKey(), 0L) <= budget.getValue());
	}

	/**
	 * Returns this attempt's own rows. Bookkeeping only: by the time it runs
	 * the login has already happened -- on a success, the token is issued and
	 * {@code token_version} bumped -- so a database error here must not turn
	 * that into a 500. A row left behind ages out with the window. The log
	 * names neither the phone nor the address.
	 */
	private void release(List<Long> reservation) {
		try {
			this.jdbcTemplate.update(
					"DELETE FROM platform_admin_login_attempts WHERE id IN ("
							+ String.join(", ", Collections.nCopies(reservation.size(), "?")) + ")",
					reservation.toArray());
		} catch (DataAccessException ex) {
			log.warn("Could not return an app login's throttle reservation; it ages out with the window", ex);
		}
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
