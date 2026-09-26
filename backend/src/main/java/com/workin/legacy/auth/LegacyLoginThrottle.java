package com.workin.legacy.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
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

import com.workin.legacy.LegacyValues;
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
 * <h2>The key and the lookup share one value by construction</h2>
 * <p>MariaDB's {@code utf8mb4_unicode_ci} matches far more than ASCII digits
 * to a stored {@code 01012345678}: Arabic-Indic, Persian and fullwidth digits,
 * but also circled and dingbat digits, Hangzhou numerals, Ethiopic and Khmer
 * number signs and more -- 94 code points outside any digit category, found
 * by comparing the whole BMP against the collation. No key derived from the
 * raw string can follow all of that. So the phone is not keyed on what the
 * client sent; the route looks up {@link #bindablePhone}, the value the key
 * is made from. That folds NFKC and every decimal digit (category Nd) to ASCII,
 * and then admits only ASCII digits, a leading {@code +}, hyphens and the
 * whitespace PHP's {@code trim()} strips. Anything else never reaches the
 * lookup: it is answered as the route answers an unknown phone and charged
 * to the address as a miss. So the key is the ASCII digits of exactly the
 * string the lookup binds, and a phone written in characters the collation
 * would have folded can no longer find an account under a different key. A
 * legitimate phone typed in Arabic-Indic digits is folded, and still finds
 * its row. A phone with no digits at all is charged to the address only,
 * never to one bucket every such phone would share.
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
	 * Runs {@code login} inside every budget.
	 *
	 * @param rawPhone the request's {@code phone}, before any conversion
	 * @param unknownPhone the route's answer to a phone that matches no
	 *        account, given to a phone {@link #bindablePhone} refuses, so the
	 *        refusal reads exactly as a miss does
	 * @param login the login, given the phone to bind in its lookup in place
	 *        of the request's
	 * @throws LegacyApiException 429 {@code too_many_login_attempts} when any
	 *         budget is spent, before the password is looked at
	 */
	public <T> T guard(Object rawPhone, String clientAddress,
			Supplier<LegacyApiException> unknownPhone, Function<String, T> login) {
		String address = clientAddress == null ? "" : clientAddress;
		String bound = bindablePhone(rawPhone);
		String phone = bound == null ? "" : asciiDigits(bound);

		Map<String, Integer> budgets = new LinkedHashMap<>();
		budgets.put(hash("api-addr:" + address), MAX_ADDRESS_MISSES);
		if (!phone.isEmpty()) {
			budgets.put(hash("api-phone:" + phone), MAX_PHONE_MISSES);
			budgets.put(hash("api-pair:" + phone + "|" + address), MAX_PAIR_MISSES);
		}

		Instant now = this.clock.instant();
		List<Long> reservation = reserve(budgets.keySet(), now);
		if (!withinBudgets(budgets, now)) {
			release(reservation);
			throw new LegacyApiException(429, "too_many_login_attempts");
		}

		if (bound == null) {
			// Refused before any lookup, and kept as a miss against the address.
			throw unknownPhone.get();
		}

		T result;
		try {
			result = login.apply(bound);
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
	 * The phone a route binds in its lookup, or {@code null} when it must not
	 * reach one: PHP's {@code (string)} cast, NFKC-folded, every decimal digit
	 * (category Nd) written as ASCII, and then only ASCII digits, a leading
	 * {@code +}, the separators a phone is written with ({@code - ( ) . /}) and
	 * {@code trim()}'s whitespace admitted. {@code register_employee.php}
	 * stores a phone as sent, so a refused separator would lock out an
	 * account whose stored phone holds it. Those are
	 * the only characters left, and none of them can compare equal to a digit
	 * under the collation, so the ASCII digits of this string are the digits
	 * the lookup matches on.
	 */
	public static String bindablePhone(Object rawPhone) {
		String folded = Normalizer.normalize(LegacyValues.toPhpString(rawPhone), Normalizer.Form.NFKC);
		StringBuilder bound = new StringBuilder(folded.length());
		boolean signAllowed = true;
		for (int index = 0; index < folded.length(); ) {
			int codePoint = folded.codePointAt(index);
			index += Character.charCount(codePoint);
			if (Character.getType(codePoint) == Character.DECIMAL_DIGIT_NUMBER) {
				int digit = Character.digit(codePoint, 10);
				if (digit < 0) {
					return null;
				}
				bound.append((char) ('0' + digit));
				signAllowed = false;
			} else if (isTrimWhitespace(codePoint)) {
				bound.append((char) codePoint);
			} else if (codePoint == '+' && signAllowed) {
				bound.append('+');
				signAllowed = false;
			} else if (codePoint == '-' || codePoint == '(' || codePoint == ')' || codePoint == '.' || codePoint == '/') {
				bound.append((char) codePoint);
				signAllowed = false;
			} else {
				return null;
			}
		}
		return bound.toString();
	}

	/** {@code trim()}'s default characters: space, tab, newline, return, NUL and vertical tab. */
	private static boolean isTrimWhitespace(int codePoint) {
		return codePoint == ' ' || codePoint == '\t' || codePoint == '\n' || codePoint == '\r'
				|| codePoint == 0 || codePoint == 0x0B;
	}

	private static String asciiDigits(String bound) {
		StringBuilder digits = new StringBuilder(bound.length());
		for (int index = 0; index < bound.length(); index++) {
			char character = bound.charAt(index);
			if (character >= '0' && character <= '9') {
				digits.append(character);
			}
		}
		return digits.toString();
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
