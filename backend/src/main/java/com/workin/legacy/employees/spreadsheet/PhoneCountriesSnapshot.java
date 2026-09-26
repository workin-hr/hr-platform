package com.workin.legacy.employees.spreadsheet;

import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import com.workin.legacy.phone.LegacyPhoneCountries;
import com.workin.legacy.phone.LegacyPhoneCountry;

/**
 * {@code phone_countries} read once for a whole sheet (D-294).
 *
 * <p>The request-scoped {@link LegacyPhoneCountries} caches only its table
 * probe; {@code find()}, {@code dialCodes()} and {@code defaultCode()} each
 * query the table again, and the update sheet asks them for every row whose
 * phone cell is filled -- and for every stored phone the peer rule compares.
 * This serves the same answers from one read of the active rows, so
 * {@link com.workin.legacy.phone.LegacyPhoneNumbers} built over it keeps
 * deciding validity exactly as it does for one number, without a statement.
 *
 * <p>{@link #find} compares as the query's {@code country_code = ?} does under
 * the table's {@code _ci} collation with {@code PAD SPACE}: case-insensitively
 * and ignoring trailing spaces. When the table is absent the rows are the
 * fallback definitions, whose scan is an exact match; their codes are ASCII
 * without padding, so the two agree.
 */
final class PhoneCountriesSnapshot extends LegacyPhoneCountries {

	private final List<LegacyPhoneCountry> active;

	/**
	 * @param dataSource required by the superclass's constructor and never
	 *        queried: every method that would reach it is overridden
	 * @param active {@link LegacyPhoneCountries#allActive()}, read once by the caller
	 */
	PhoneCountriesSnapshot(DataSource dataSource, List<LegacyPhoneCountry> active) {
		super(dataSource);
		this.active = List.copyOf(active);
	}

	@Override
	public List<LegacyPhoneCountry> allActive() {
		return this.active;
	}

	@Override
	public Optional<LegacyPhoneCountry> find(String countryCode) {
		String code = countryCode == null ? "" : countryCode.trim();
		if (code.isEmpty()) {
			return Optional.empty();
		}
		return this.active.stream()
				.filter(row -> row.countryCode() != null && padSpaceTrimmed(row.countryCode()).equalsIgnoreCase(code))
				.findFirst();
	}

	@Override
	public List<String> offeredDialCodes() {
		return dialCodes();
	}

	private static String padSpaceTrimmed(String value) {
		int end = value.length();
		while (end > 0 && value.charAt(end - 1) == ' ') {
			end--;
		}
		return value.substring(0, end);
	}
}
