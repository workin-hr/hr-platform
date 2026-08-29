package com.workin.legacy.auth.registration;

import java.util.Locale;
import java.util.regex.Pattern;

import com.workin.legacy.LegacyValues;

/**
 * {@code helpers/company_code_helper.php}'s pure half -- the public join code
 * a company hands its employees.
 *
 * <p>Normalisation is {@code strtoupper(trim($code))} and validation is
 * {@code /^[A-Za-z0-9]{5,32}$/} applied to a <b>separately trimmed</b> value.
 * The two are not composed in PHP, which matters in one place: validation
 * accepts mixed case even though every lookup upper-cases first, so
 * {@code company_code_is_valid()} is really a shape test and never a
 * canonicality test.
 */
public final class LegacyCompanyCode {

	private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9]{5,32}$");

	private LegacyCompanyCode() {
	}

	/** {@code company_code_normalize()}. */
	public static String normalize(Object code) {
		return LegacyValues.phpTrim(code == null ? "" : LegacyValues.toPhpString(code))
				.toUpperCase(Locale.ROOT);
	}

	/**
	 * {@code company_code_is_valid()}.
	 *
	 * <p>{@code strtoupper()} is byte-wise in PHP and does not touch non-ASCII,
	 * and the pattern is ASCII-only, so a code containing Arabic digits or
	 * letters is rejected here rather than silently mangled.
	 */
	public static boolean isValid(Object code) {
		return VALID.matcher(
				LegacyValues.phpTrim(code == null ? "" : LegacyValues.toPhpString(code))).matches();
	}
}
