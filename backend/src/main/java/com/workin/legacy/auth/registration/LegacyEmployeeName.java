package com.workin.legacy.auth.registration;

import java.util.Map;
import java.util.regex.Pattern;

import com.workin.legacy.LegacyValues;

/**
 * {@code split_full_name()} and {@code resolve_employee_name_from_body()}
 * ({@code helpers/functions.php:183-223}).
 */
public final class LegacyEmployeeName {

	/** {@code preg_replace('/\s+/u', ' ', $full)} -- Unicode-aware whitespace. */
	private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

	private LegacyEmployeeName() {
	}

	/** The pair {@code resolve_employee_name_from_body()} returns. */
	public record Name(String firstName, String lastName) {
	}

	/**
	 * {@code split_full_name()}: everything before the <b>first</b> space is the
	 * first name and the rest, trimmed, is the last name -- so
	 * {@code "Ana Maria de Souza"} splits to {@code "Ana"} /
	 * {@code "Maria de Souza"} and not the other way round.
	 *
	 * <p>Runs of whitespace collapse to one space first, which is what makes
	 * {@code "Ana   Maria"} behave like {@code "Ana Maria"}.
	 */
	public static Name split(String full) {
		String collapsed = LegacyValues.phpTrim(
				WHITESPACE_RUN.matcher(full == null ? "" : full).replaceAll(" "));
		if (collapsed.isEmpty()) {
			return new Name("", "");
		}
		int space = collapsed.indexOf(' ');
		if (space < 0) {
			return new Name(collapsed, "");
		}
		return new Name(collapsed.substring(0, space),
				LegacyValues.phpTrim(collapsed.substring(space + 1)));
	}

	/**
	 * {@code resolve_employee_name_from_body()}.
	 *
	 * <p>Three steps, and the second only runs when <b>both</b> parts are
	 * empty: an explicit {@code first_name} with no {@code last_name} does not
	 * fall back to splitting {@code name}. The third gives an unnamed applicant
	 * the literal first name {@code "Pending-<phone>"}, which is why a join
	 * request always has something to display.
	 *
	 * <h2>Which of these {@code join_company.php} can actually reach</h2>
	 * <p>That endpoint calls {@code required($body, [FIRST_NAME, ...])} first,
	 * so a body supplying only {@code name} never gets here — the
	 * <b>splitting</b> fallback is unreachable from it. The
	 * {@code Pending-<phone>} fallback <b>is</b> reachable, by exactly one
	 * input shape: {@code required()} is {@code isset() && !== ''} rather than
	 * a trim, so {@code "first_name": "  "} passes the guard and is trimmed to
	 * empty here. {@code aFullNameFallback...} asserts the stored name for that
	 * body, because the distinction is one bullet wide and was documented
	 * wrongly once already.
	 */
	public static Name fromBody(Map<String, Object> body, String phoneFallback) {
		String first = trimmed(body, "first_name");
		String last = trimmed(body, "last_name");
		if (first.isEmpty() && last.isEmpty()) {
			String full = trimmed(body, "name");
			if (!full.isEmpty()) {
				Name split = split(full);
				first = split.firstName();
				last = split.lastName();
			}
		}
		if (first.isEmpty() && phoneFallback != null && !phoneFallback.isEmpty()) {
			first = "Pending-" + phoneFallback;
		}
		return new Name(first, last);
	}

	private static String trimmed(Map<String, Object> body, String key) {
		Object value = body == null ? null : body.get(key);
		return LegacyValues.phpTrim(value == null ? "" : LegacyValues.toPhpString(value));
	}

	/** {@code normalize_optional_email()}: trim, and empty becomes null. */
	public static String normalizeOptionalEmail(Object value) {
		String email = LegacyValues.phpTrim(value == null ? "" : LegacyValues.toPhpString(value));
		return email.isEmpty() ? null : email;
	}
}
