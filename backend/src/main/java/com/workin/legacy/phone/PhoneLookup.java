package com.workin.legacy.phone;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.workin.legacy.LegacyValues;

/**
 * How a phone number finds stored rows without binding what the client sent
 * (ADR-0020).
 *
 * <p>A lookup is two steps. {@link #clause} narrows the candidates with
 * {@code phone IN (...)} over the bounded set of digit spellings each possible
 * reading of the number can be stored in -- which uses the column's unique
 * index and binds nothing the client wrote. {@link #matches} then keeps only
 * the rows whose own stored {@code (phone, country_code)} canonicalises to
 * the same E.164 number. Only verified rows are the answer; a caller that
 * skips {@link #matches} has a candidate list, not a lookup.
 *
 * <p>Two kinds:
 * <ul>
 * <li>{@link #of(CanonicalPhone)}: one known number -- the uniqueness checks,
 *     which have already validated what they are about to write.</li>
 * <li>{@link #ofInput}: a number typed with no country, as every login and
 *     OTP route receives it. Written internationally it has one reading.
 *     Written nationally it is read in Egypt and in every country the product
 *     offers; a stored row matches only when the input, read in <em>that
 *     row's</em> country, is that row's number. So {@code 0501234567} finds
 *     the Saudi account stored as {@code 0501234567} under {@code +966}, and
 *     is not a guess: the row's own country decided it.</li>
 * </ul>
 * {@link #e164s()} lists every reading, which is what the login throttle
 * charges -- so any account this lookup can reach was charged under its own
 * number.
 */
public final class PhoneLookup {

	private final Map<String, CanonicalPhone> readings;

	/** The raw input for {@link #ofInput}; null for a known number. */
	private final Object input;

	private final boolean inputIsNational;

	private PhoneLookup(Map<String, CanonicalPhone> readings, Object input, boolean inputIsNational) {
		this.readings = readings;
		this.input = input;
		this.inputIsNational = inputIsNational;
	}

	/** The rows holding exactly this number, in any stored spelling. */
	public static PhoneLookup of(CanonicalPhone phone) {
		Map<String, CanonicalPhone> readings = new LinkedHashMap<>();
		readings.put(phone.e164(), phone);
		return new PhoneLookup(readings, null, false);
	}

	/**
	 * The rows a number typed without a country refers to.
	 *
	 * @param offeredDialCodes the dial codes a national number may be read in,
	 *        besides Egypt's; asked only when the input is written nationally
	 */
	public static PhoneLookup ofInput(Object raw, Supplier<? extends Collection<String>> offeredDialCodes) {
		Map<String, CanonicalPhone> readings = new LinkedHashMap<>();
		Optional<CanonicalPhone> home = CanonicalPhones.parse(raw, null);
		home.ifPresent(phone -> readings.put(phone.e164(), phone));
		boolean national = isNational(raw);
		if (national) {
			for (String dialCode : offeredDialCodes.get()) {
				CanonicalPhones.parse(raw, dialCode).ifPresent(phone -> readings.putIfAbsent(phone.e164(), phone));
			}
		}
		return new PhoneLookup(readings, raw, national);
	}

	/** True when nothing valid was read: a caller must not query at all. */
	public boolean isEmpty() {
		return this.readings.isEmpty();
	}

	/** Every E.164 number this lookup can match, in reading order (Egypt first). */
	public Set<String> e164s() {
		return Collections.unmodifiableSet(new LinkedHashSet<>(this.readings.keySet()));
	}

	/** The readings themselves. */
	public List<CanonicalPhone> readings() {
		return List.copyOf(this.readings.values());
	}

	/** The single reading of a known number, or of input that had only one. */
	public Optional<CanonicalPhone> only() {
		return this.readings.size() == 1 ? Optional.of(this.readings.values().iterator().next()) : Optional.empty();
	}

	/** The digit spellings a candidate row may hold, across every reading. */
	public List<String> spellings() {
		LinkedHashSet<String> spellings = new LinkedHashSet<>();
		for (CanonicalPhone phone : this.readings.values()) {
			spellings.addAll(phone.storedSpellings());
		}
		return List.copyOf(spellings);
	}

	/**
	 * {@code column IN (?, ...)} and its binds, or {@code 0=1} and none when
	 * nothing was read -- a query built from an empty lookup matches nothing.
	 */
	public Clause clause(String column) {
		List<String> spellings = spellings();
		if (spellings.isEmpty()) {
			return new Clause("0=1", List.of());
		}
		return new Clause(column + " IN (" + String.join(", ", Collections.nCopies(spellings.size(), "?")) + ")",
				spellings);
	}

	/**
	 * Whether a stored row is this number: its {@code phone} read in its own
	 * {@code country_code} (Egypt when blank) is one of {@link #e164s()}, and,
	 * for national input, is what the input reads as in that same country.
	 */
	public boolean matches(Object storedPhone, Object storedCountryCode) {
		String country = CanonicalPhones.countryCodeAsRead(storedCountryCode);
		Optional<CanonicalPhone> stored = CanonicalPhones.parse(storedPhone, country);
		if (stored.isEmpty() || !this.readings.containsKey(stored.get().e164())) {
			return false;
		}
		if (this.input == null || !this.inputIsNational) {
			return true;
		}
		Optional<CanonicalPhone> inRowsCountry = CanonicalPhones.parse(this.input, country);
		return inRowsCountry.isPresent() && inRowsCountry.get().e164().equals(stored.get().e164());
	}

	/** Keeps the rows that {@link #matches}, in their order. */
	public List<Map<String, Object>> verified(List<Map<String, Object>> rows) {
		return verified(rows, "phone", "country_code");
	}

	/** {@link #verified(List)} for rows whose columns are named otherwise. */
	public List<Map<String, Object>> verified(List<Map<String, Object>> rows, String phoneColumn, String countryColumn) {
		List<Map<String, Object>> kept = new ArrayList<>(rows.size());
		for (Map<String, Object> row : rows) {
			if (matches(row.get(phoneColumn), row.get(countryColumn))) {
				kept.add(row);
			}
		}
		return kept;
	}

	/**
	 * The one row a route that answers for a single account acts on, out of
	 * rows already {@link #verified}: the only row, or -- when the number is
	 * stored more than once, as the duplicate company registrations ADR-0020
	 * counts are -- the row stored exactly as the request's digits. Null when
	 * neither decides; the route then answers as it answers an unknown phone,
	 * rather than choosing an account for the caller.
	 */
	public static Map<String, Object> singleRow(List<Map<String, Object>> verified, Object typedPhone) {
		if (verified.size() == 1) {
			return verified.get(0);
		}
		String typed = typedDigits(typedPhone);
		if (typed.isEmpty()) {
			return null;
		}
		Map<String, Object> exact = null;
		for (Map<String, Object> row : verified) {
			if (typed.equals(LegacyValues.toPhpString(row.get("phone")))) {
				if (exact != null) {
					return null;
				}
				exact = row;
			}
		}
		return exact;
	}

	/** The ASCII digits of what the client typed, folded as {@link CanonicalPhones} folds it. */
	private static String typedDigits(Object typedPhone) {
		try {
			return CanonicalPhones.fold(LegacyValues.toPhpString(typedPhone))
					.map(CanonicalPhones::asciiDigits).orElse("");
		} catch (RuntimeException ex) {
			return "";
		}
	}

	/**
	 * Whether the raw input is written nationally: its first {@code +} or
	 * digit (after folding) is neither {@code +} nor the start of {@code 00}.
	 */
	private static boolean isNational(Object raw) {
		String text;
		try {
			text = LegacyValues.toPhpString(raw);
		} catch (RuntimeException ex) {
			return false;
		}
		Optional<String> folded = CanonicalPhones.fold(text);
		if (folded.isEmpty()) {
			return false;
		}
		String input = folded.get();
		for (int index = 0; index < input.length(); index++) {
			char character = input.charAt(index);
			if (character == '+') {
				return false;
			}
			if (character >= '0' && character <= '9') {
				return !input.startsWith("00", index);
			}
		}
		return false;
	}

	/** A {@code WHERE} fragment and its binds, kept together so they cannot drift apart. */
	public record Clause(String sql, List<String> binds) {
	}
}
