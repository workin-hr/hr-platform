package com.workin.legacy.phone;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyValues;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The phone helpers the legacy surface calls, now split in two (ADR-0020,
 * D-291).
 *
 * <p><b>Validity and identity are {@link CanonicalPhones}'</b>, which is
 * Google libphonenumber's metadata. This class used to be a literal port of
 * PHP's hand-written rules and said it was deliberately <em>not</em>
 * libphonenumber; the owner reversed that on 2026-09-26, and the regexes,
 * the lookup variants and the {@code REPLACE()} match clause are gone. What
 * this class adds on top is the product's policy, which the library cannot
 * know:
 * <ul>
 * <li>{@link #forAccount}: the numbers an account may be <em>given</em> --
 *     valid, mobile (legacy never accepted a landline), and in a country the
 *     product offers ({@link #offeredDialCodes()});</li>
 * <li>{@link #lookup}: the rows a number typed without a country refers to,
 *     read in Egypt and in every offered country;</li>
 * <li>the formatting helpers PHP's spreadsheets and dial-code selectors still
 *     need ({@link #excelCellToRaw}, {@link #decodePrefixes},
 *     {@link #normalizeDialCode}, {@link #resolveCode}), none of which decides
 *     whether a number is valid.</li>
 * </ul>
 */
@Service
public class LegacyPhoneNumbers {

	private static final Pattern NON_DIGITS = Pattern.compile("\\D+");

	/** {@link CanonicalPhones#DEFAULT_REGION}'s dial code: what a blank {@code country_code} reads as. */
	private static final String DEFAULT_DIAL_CODE = "+20";
	private static final Pattern PREFIX_SEPARATORS = Pattern.compile("[\\s,;]+");
	private static final Pattern SCIENTIFIC = Pattern.compile("^\\d+\\.?\\d*E[+-]?\\d+$", Pattern.CASE_INSENSITIVE);
	private static final Pattern TRAILING_ZEROS = Pattern.compile("^\\d+\\.0+$");

	/** The application's own mapper (Jackson 3, already on the classpath). */
	private static final ObjectMapper JSON = new ObjectMapper();

	/** Egypt's dial code, accepted whatever {@code phone_countries} holds, as PHP's special case did. */
	static final String HOME_DIAL_CODE = "+20";

	/**
	 * The countries {@code phone_is_valid_local_legacy()} accepted when
	 * {@code phone_countries} had no row for them -- so PHP accepted them
	 * whatever the table held, and so does this, rather than refusing a
	 * Saudi or Emirati number because a deployment's table omits the row.
	 */
	static final List<String> ALWAYS_OFFERED = List.of(HOME_DIAL_CODE, "+966", "+971");

	private final LegacyPhoneCountries countries;

	public LegacyPhoneNumbers(LegacyPhoneCountries countries) {
		this.countries = countries;
	}

	/** {@code phone_digits_only()}: {@code preg_replace('/\D+/', '', $phone)}. */
	public static String digitsOnly(String phone) {
		return phone == null ? "" : NON_DIGITS.matcher(phone).replaceAll("");
	}

	/**
	 * {@code phone_excel_cell_to_raw()}: spreadsheet cells arrive as floats,
	 * scientific notation or a trailing {@code .0}, and all three have to become
	 * the digit string a human typed.
	 *
	 * <p>Java sees the same shapes through Jackson: an integral JSON number is
	 * an {@code Integer}/{@code Long}, a fractional one a {@code Double}, so the
	 * type branches are kept rather than collapsed into {@code toString()}.
	 */
	public static String excelCellToRaw(Object value) {
		if (value == null) {
			return "";
		}
		if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
			return value.toString();
		}
		if (value instanceof Float || value instanceof Double) {
			// sprintf('%.0f', $value) -- no exponent, no decimal point.
			return new BigDecimal(value.toString()).setScale(0, java.math.RoundingMode.HALF_UP).toPlainString();
		}
		if (value instanceof BigDecimal decimal) {
			return decimal.setScale(0, java.math.RoundingMode.HALF_UP).toPlainString();
		}
		String raw = value.toString().trim();
		if (raw.isEmpty()) {
			return "";
		}
		if (SCIENTIFIC.matcher(raw).matches()) {
			return new BigDecimal(raw).setScale(0, java.math.RoundingMode.HALF_UP).toPlainString();
		}
		if (TRAILING_ZEROS.matcher(raw).matches()) {
			return raw.substring(0, raw.indexOf('.'));
		}
		return raw;
	}

	/**
	 * {@code phone_country_decode_prefixes()}: {@code json_decode($raw, true)}
	 * first, then a {@code [\s,;]+} split of whatever else the column held,
	 * then digits-only, blanks dropped, duplicates removed, order preserved.
	 *
	 * <p>Decoding uses the application's own Jackson mapper rather than a
	 * hand-written parser, because PHP's decoder is a real JSON decoder: a
	 * comma inside a string ({@code ["01,0"]}) and an escape sequence
	 * ({@code ["010"]}) both have to survive it, and a split
	 * on {@code ,} silently corrupts the first into two prefixes.
	 *
	 * <p><b>A JSON object root takes the array branch, not the fallback.</b>
	 * {@code json_decode($raw, true)} turns an object into an associative array
	 * and {@code is_array()} is then true, so PHP iterates the object's
	 * <em>values</em>. Verified against PHP 8.3: {@code {"a":"01,0"}} yields
	 * {@code ["010"]}, where a delimiter split would have yielded
	 * {@code ["01","0"]}. Only a root that decodes to a scalar or fails to
	 * decode reaches the split.
	 *
	 * <p>Element coercion is PHP's {@code (string)} cast, which is what
	 * {@link LegacyValues#toPhpString} already reproduces: numbers stringify,
	 * {@code true} becomes {@code "1"}, {@code false} and {@code null} become
	 * the empty string, and a nested array or object becomes {@code "Array"} --
	 * contributing no digits, so {@code [{"prefix":"010"},["011"]]} decodes to
	 * nothing at all.
	 */
	public static List<String> decodePrefixes(Object raw) {
		if (raw == null || "".equals(raw)) {
			return List.of();
		}
		List<String> items;
		if (raw instanceof Iterable<?> iterable) {
			items = new ArrayList<>();
			iterable.forEach(item -> items.add(LegacyValues.toPhpString(item)));
		} else {
			String text = raw.toString();
			items = decodeJsonArrayOrObject(text).orElseGet(() -> {
				List<String> split = new ArrayList<>();
				for (String part : PREFIX_SEPARATORS.split(text)) {
					if (!part.isEmpty()) {
						split.add(part);
					}
				}
				return split;
			});
		}
		LinkedHashSet<String> unique = new LinkedHashSet<>();
		for (String item : items) {
			String digits = digitsOnly(item);
			if (!digits.isEmpty()) {
				unique.add(digits);
			}
		}
		return List.copyOf(unique);
	}

	/** {@code phone_country_normalize_dial_code()}: {@code 20} and {@code 020} both become {@code +20}. */
	public static String normalizeDialCode(String countryCode) {
		String code = countryCode == null ? "" : countryCode.trim();
		if (code.isEmpty()) {
			return "";
		}
		if (!code.startsWith("+")) {
			String digits = digitsOnly(code);
			if (!digits.isEmpty()) {
				code = "+" + digits;
			}
		}
		return code;
	}

	/** {@code phone_country_resolve_code()}: a known dial code, else the first configured one, else {@code +20}. */
	public String resolveCode(String countryCode) {
		String code = normalizeDialCode(countryCode);
		List<String> codes = countries.dialCodes();
		if (codes.isEmpty()) {
			return "+20";
		}
		if (!code.isEmpty() && codes.contains(code)) {
			return code;
		}
		return codes.get(0);
	}

	/**
	 * A number an account may be given, in the storage convention every Java
	 * write uses: {@link CanonicalPhone#nationalDigits()} in {@code phone} and
	 * {@link CanonicalPhone#dialCode()} in {@code country_code}.
	 *
	 * <p>Valid per the metadata, typed mobile -- legacy's rules accepted only
	 * mobile ranges in every country they knew, so a landline stays refused --
	 * and in a country the product offers. Input written internationally keeps
	 * its own country whatever {@code countryContext} says, and the caller
	 * must store {@link CanonicalPhone#dialCode()}, never the request's code.
	 *
	 * @param countryContext the dial code a national number is read in; blank
	 *        for Egypt
	 */
	public Optional<CanonicalPhone> forAccount(Object raw, String countryContext) {
		Optional<CanonicalPhone> phone = CanonicalPhones.parse(raw, countryContext);
		if (phone.isEmpty() || !phone.get().mobile()) {
			return Optional.empty();
		}
		return offered(phone.get()) ? phone : Optional.empty();
	}

	/**
	 * Whether the number is in a country the product offers -- the set
	 * {@link #forAccount} admits, and the only numbers an OTP is delivered to.
	 */
	public boolean offered(CanonicalPhone phone) {
		String dialCode = phone.dialCode();
		return ALWAYS_OFFERED.contains(dialCode) || offeredDialCodes().contains(dialCode);
	}

	/**
	 * What a write carrying a {@code country_code} but no {@code phone} must
	 * validate, or {@code null} when it leaves the number alone. A national
	 * number is read in its row's {@code country_code}, so changing the code
	 * alone can change which number the row is.
	 *
	 * <p>Codes are compared as they are <em>read</em>: a blank one reads as
	 * Egypt's, and {@code 20} as {@code +20}, so a code under which the stored
	 * phone is the same number as before -- or the very code already stored --
	 * is no change, and the caller writes it as read
	 * ({@link #countryCodeWritten}), as PHP did less its padding. Any other
	 * code is a change, and the caller runs the stored phone through
	 * {@link #forAccount} in {@link Reread#countryCode()} (a blank one as
	 * Egypt's dial code) and its own uniqueness check, as for a new phone,
	 * storing the number's own dial code.
	 */
	public static Reread storedPhoneRereadBy(Object newCountryCode, Object storedPhone, Object storedCountryCode) {
		String stored = storedPhone == null ? "" : LegacyValues.phpTrim(LegacyValues.toPhpString(storedPhone));
		if (digitsOnly(stored).isEmpty()) {
			return null;
		}
		String before = Objects.toString(CanonicalPhones.countryCodeAsRead(storedCountryCode), "");
		String after = Objects.toString(CanonicalPhones.countryCodeAsRead(newCountryCode), "");
		if (after.equals(before)) {
			return null;
		}
		Optional<CanonicalPhone> was = CanonicalPhones.parse(stored, before);
		Optional<CanonicalPhone> is = CanonicalPhones.parse(stored, after);
		if (was.isPresent() && is.isPresent() && was.get().e164().equals(is.get().e164())) {
			return null;
		}
		return new Reread(stored, after.isEmpty() ? DEFAULT_DIAL_CODE : after);
	}

	/**
	 * The value a {@code country_code} that changes no number is stored as: a
	 * string as {@link CanonicalPhones#countryCodeAsRead} reads it -- so a
	 * padded {@code "+966\0"} is stored {@code "+966"}, never a value the
	 * readers would take for another code -- and anything else (null, a JSON
	 * number) as sent.
	 */
	public static Object countryCodeWritten(Object countryCode) {
		return countryCode instanceof String text ? CanonicalPhones.countryCodeAsRead(text) : countryCode;
	}

	/**
	 * The stored phone and the non-blank code to read it in.
	 *
	 * @param phone the stored phone, trimmed
	 * @param countryCode the written code, or Egypt's for a blank one
	 */
	public record Reread(String phone, String countryCode) {
	}

	/**
	 * The stored rows a number typed with no country refers to -- every login
	 * and OTP route's input. The offered countries are read only for a number
	 * written nationally; international input needs no table.
	 */
	public PhoneLookup lookup(Object raw) {
		return PhoneLookup.ofInput(raw, this::offeredDialCodes);
	}

	/**
	 * The dial codes the product offers: {@code phone_countries}' active rows
	 * (or its fallback rows when the table is absent), plus
	 * {@link #ALWAYS_OFFERED}. Validity inside each is the metadata's.
	 */
	public List<String> offeredDialCodes() {
		LinkedHashSet<String> codes = new LinkedHashSet<>(ALWAYS_OFFERED);
		for (String code : countries.offeredDialCodes()) {
			codes.add(normalizeDialCode(code));
		}
		return List.copyOf(codes);
	}

	/**
	 * {@code json_decode($raw, true)} plus {@code is_array($decoded)}.
	 *
	 * <p>Returns the values to iterate when the root decodes to a JSON array or
	 * object (both are PHP arrays under {@code assoc = true}), and nothing when
	 * it decodes to a scalar or does not decode at all -- the two cases that
	 * send PHP to the delimiter split.
	 */
	private static Optional<List<String>> decodeJsonArrayOrObject(String text) {
		Object decoded;
		try {
			decoded = JSON.readValue(text, Object.class);
		} catch (JacksonException ex) {
			return Optional.empty();
		}
		Collection<?> values;
		if (decoded instanceof List<?> list) {
			values = list;
		} else if (decoded instanceof Map<?, ?> map) {
			values = map.values();
		} else {
			return Optional.empty();
		}
		List<String> items = new ArrayList<>();
		for (Object value : values) {
			items.add(LegacyValues.toPhpString(value));
		}
		return Optional.of(items);
	}

}
