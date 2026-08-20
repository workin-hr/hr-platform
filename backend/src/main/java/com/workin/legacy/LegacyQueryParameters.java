package com.workin.legacy;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The subset of PHP query-string parsing needed by legacy endpoints before they apply explicit
 * scalar casts.
 *
 * <p>Servlet parameter binding can flatten {@code name[]=value} into a scalar controller argument,
 * but PHP's request parser first turns that syntax into an array. Parsing the raw query string here
 * preserves that distinction. PHP 8.3 {@code parse_str()} also normalizes dots and spaces in external
 * parameter names to underscores and makes ordering significant: a plain assignment replaces any
 * earlier value, an array assignment replaces an earlier scalar and starts an array, and subsequent
 * append or keyed assignments extend that array.
 *
 * <p>This intentionally implements the one-dimensional forms used by the migrated endpoints:
 * plain keys, append arrays ({@code name[]}), and keyed arrays ({@code name[key]}). Nested PHP arrays
 * remain outside this compatibility boundary.
 */
public final class LegacyQueryParameters {

	private final Map<String, ParsedValue> values;

	private LegacyQueryParameters(Map<String, ParsedValue> values) {
		this.values = values;
	}

	/** Parse an HTTP query string using PHP's external-name and ordered scalar/array rules. */
	public static LegacyQueryParameters parse(String rawQuery) {
		Map<String, ParsedValue> parsed = new LinkedHashMap<>();
		if (rawQuery == null || rawQuery.isEmpty()) {
			return new LegacyQueryParameters(parsed);
		}

		for (String component : rawQuery.split("&", -1)) {
			int equals = component.indexOf('=');
			String encodedName = equals < 0 ? component : component.substring(0, equals);
			String encodedValue = equals < 0 ? "" : component.substring(equals + 1);
			ParsedName name = ParsedName.parse(decode(encodedName));
			String value = decode(encodedValue);

			if (name.baseName().isEmpty()) {
				continue;
			}
			if (!name.array()) {
				parsed.put(name.baseName(), ParsedValue.scalar(value));
			} else if (name.arrayKey().isEmpty()) {
				parsed.put(name.baseName(), append(parsed.get(name.baseName()), value));
			} else {
				parsed.put(name.baseName(), assignKey(parsed.get(name.baseName()), name.arrayKey(), value));
			}
		}
		return new LegacyQueryParameters(parsed);
	}

	/**
	 * Return the PHP-parsed request value: {@link String} for a scalar, {@link List} for an append-only
	 * array, {@link Map} for a keyed or mixed array, or {@code null} when the parameter is absent.
	 */
	public Object value(String name) {
		ParsedValue parsed = values.get(name);
		return parsed == null ? null : parsed.value();
	}

	private static String decode(String value) {
		return URLDecoder.decode(value, StandardCharsets.UTF_8);
	}

	private static ParsedValue append(ParsedValue existing, String value) {
		if (existing == null || !existing.array()) {
			return ParsedValue.array(List.of(value));
		}
		if (existing.value() instanceof List<?>) {
			List<String> items = new ArrayList<>(existing.listValues());
			items.add(value);
			return ParsedValue.array(items);
		}

		Map<Object, String> items = new LinkedHashMap<>(existing.mapValues());
		items.put(nextNumericKey(items), value);
		return ParsedValue.array(items);
	}

	private static ParsedValue assignKey(ParsedValue existing, String key, String value) {
		Map<Object, String> items = new LinkedHashMap<>();
		if (existing != null && existing.array()) {
			if (existing.value() instanceof List<?>) {
				long index = 0;
				for (String item : existing.listValues()) {
					items.put(index++, item);
				}
			} else {
				items.putAll(existing.mapValues());
			}
		}
		items.put(phpArrayKey(key), value);
		return ParsedValue.array(items);
	}

	private static Object phpArrayKey(String key) {
		// PHP's canonical-decimal-integer rule is (string)(int)$key === $key, which specifically
		// excludes "-0": (int)"-0" is 0, but (string)0 is "0", not "-0", so it stays a string key.
		if (key.matches("0|-?[1-9]\\d*")) {
			try {
				return Long.valueOf(key);
			} catch (NumberFormatException ignored) {
				// PHP retains an integer-looking key as a string when it exceeds platform bounds.
			}
		}
		return key;
	}

	private static long nextNumericKey(Map<Object, String> values) {
		long highest = -1;
		for (Object key : values.keySet()) {
			if (key instanceof Long numeric && numeric >= highest) {
				highest = numeric;
			}
		}
		if (highest == Long.MAX_VALUE) {
			throw new LegacyValueException("PHP query array cannot append beyond the platform integer bound");
		}
		return highest + 1;
	}

	private record ParsedName(String baseName, String arrayKey, boolean array) {

		private static ParsedName parse(String rawName) {
			int open = rawName.indexOf('[');
			int close = rawName.lastIndexOf(']');
			boolean oneDimensionalArray = open > 0 && close == rawName.length() - 1
					&& rawName.indexOf('[', open + 1) < 0 && rawName.indexOf(']', open + 1) == close;
			if (oneDimensionalArray) {
				return new ParsedName(normalize(rawName.substring(0, open)),
						rawName.substring(open + 1, close), true);
			}
			return new ParsedName(normalize(rawName), "", false);
		}

		private static String normalize(String name) {
			return name.replace('.', '_').replace(' ', '_');
		}
	}

	private record ParsedValue(Object value, boolean array) {

		private static ParsedValue scalar(String value) {
			return new ParsedValue(value, false);
		}

		private static ParsedValue array(List<String> values) {
			return new ParsedValue(List.copyOf(values), true);
		}

		private static ParsedValue array(Map<Object, String> values) {
			return new ParsedValue(Collections.unmodifiableMap(new LinkedHashMap<>(values)), true);
		}

		@SuppressWarnings("unchecked")
		private List<String> listValues() {
			return (List<String>) value;
		}

		@SuppressWarnings("unchecked")
		private Map<Object, String> mapValues() {
			return (Map<Object, String>) value;
		}
	}
}
