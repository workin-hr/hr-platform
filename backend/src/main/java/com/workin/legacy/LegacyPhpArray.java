package com.workin.legacy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A decoded JSON value seen the way {@code json_decode($json, true)} leaves it:
 * one ordered structure whose keys are integers or strings.
 *
 * <h2>Why this is not a List or a Map</h2>
 * <p>{@code json_decode(..., true)} collapses JSON arrays and JSON objects into
 * the same PHP array, so {@code {"rows":[{…}]}} and {@code {"rows":{"0":{…}}}}
 * are indistinguishable afterwards -- and both are indistinguishable from
 * {@code {"rows":{"foo":{…}}}} in <em>type</em>, while differing sharply in what
 * the loop over them then does. Modelling {@code rows} as a {@code List} would
 * lose the string keys; modelling it as a {@code Map} would lose the fact that
 * numeric keys are integers. Measured under PHP 8.3:
 *
 * <pre>
 * {"rows":[{…}]}          key 0     (integer)   $index + 1 = 1
 * {"rows":{"0":{…}}}      key 0     (integer)   $index + 1 = 1
 * {"rows":{"5":{…}}}      key 5     (integer)   $index + 1 = 6
 * {"rows":{"foo":{…}}}    key "foo" (string)    $index + 1 = TypeError
 * </pre>
 *
 * <p>That last row is the one that matters: {@code $index + 1} is only ever
 * evaluated when a <em>failure</em> row is built, so a string-keyed row that
 * imports cleanly is fine, while the same row failing raises
 * {@code Unsupported operand types: string + int} and takes the whole request
 * to a 500.
 *
 * <p>Which keys become integers is PHP's canonical rule, confirmed at both
 * ends: {@code "0"}, {@code "5"}, {@code "-1"} and
 * {@code "9223372036854775807"} are integers, while {@code "05"},
 * {@code "007"}, {@code "+1"}, {@code "1.5"}, {@code " 1"}, {@code "1e2"},
 * {@code ""} and {@code "9223372036854775808"} stay strings.
 */
public final class LegacyPhpArray {

	/** A decimal integer with no leading zeros, no plus sign and no surrounding space. */
	private static final Pattern CANONICAL_INTEGER = Pattern.compile("^(0|-?[1-9][0-9]*)$");

	private final List<Entry> entries;

	private LegacyPhpArray(List<Entry> entries) {
		this.entries = List.copyOf(entries);
	}

	/** One key/value pair. {@code key} is a {@link Long} or a {@link String}, never anything else. */
	public record Entry(Object key, Object value) {

		/**
		 * {@code $index + 1}. PHP's {@code +} has no string overload, so a
		 * string key raises a {@code TypeError} here rather than coercing.
		 */
		public long indexPlusOne() {
			if (key instanceof Long integer) {
				return integer + 1;
			}
			throw new LegacyPhpTypeError("Unsupported operand types: string + int");
		}

	}

	/** PHP's {@code TypeError} for {@code "foo" + 1}, which nothing in this module catches. */
	public static class LegacyPhpTypeError extends RuntimeException {

		public LegacyPhpTypeError(String message) {
			super(message);
		}

	}

	/**
	 * Builds the array a decoded JSON value corresponds to. A JSON array gives
	 * integer keys counting from zero; a JSON object gives each key the type
	 * PHP would give it. Anything else -- a scalar, or null -- is not an array
	 * at all, which callers test with {@link #isArray(Object)}.
	 */
	public static LegacyPhpArray of(Object decoded) {
		List<Entry> entries = new ArrayList<>();
		if (decoded instanceof List<?> array) {
			for (int index = 0; index < array.size(); index++) {
				entries.add(new Entry((long) index, array.get(index)));
			}
			return new LegacyPhpArray(entries);
		}
		if (decoded instanceof Map<?, ?> object) {
			object.forEach((key, value) -> entries.add(new Entry(keyOf(String.valueOf(key)), value)));
			return new LegacyPhpArray(entries);
		}
		throw new IllegalArgumentException("not a PHP array: " + decoded);
	}

	/**
	 * {@code is_array($value)}: true for a JSON array or object, and for the
	 * {@code null} that {@code ?? []} turns into an empty array. False for every
	 * scalar.
	 */
	public static boolean isArray(Object decoded) {
		return decoded == null || decoded instanceof List<?> || decoded instanceof Map<?, ?>;
	}

	/** {@code $value === []}. */
	public boolean isEmpty() {
		return entries.isEmpty();
	}

	public List<Entry> entries() {
		return entries;
	}

	/** The empty array {@code ?? []} produces. */
	public static LegacyPhpArray empty() {
		return new LegacyPhpArray(List.of());
	}

	private static Object keyOf(String key) {
		if (!CANONICAL_INTEGER.matcher(key).matches()) {
			return key;
		}
		try {
			return Long.valueOf(key);
		} catch (NumberFormatException ex) {
			// Beyond the 64-bit range, which PHP also leaves as a string.
			return key;
		}
	}

}
