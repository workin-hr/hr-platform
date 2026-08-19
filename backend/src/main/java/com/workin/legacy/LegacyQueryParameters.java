package com.workin.legacy;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The subset of PHP query-string parsing needed by legacy endpoints before they apply explicit
 * scalar casts.
 *
 * <p>Servlet parameter binding can flatten {@code name[]=value} into a scalar controller argument,
 * but PHP's request parser first turns that syntax into an array. Parsing the raw query string here
 * preserves that distinction. PHP 8.3 {@code parse_str()} also makes ordering significant: a plain
 * assignment replaces any earlier value, an append assignment replaces an earlier scalar and starts
 * an array, and subsequent append assignments extend that array.
 *
 * <p>This intentionally implements only plain keys and the append form {@code name[]}; nested and
 * keyed PHP arrays are outside the request shapes used by the migrated endpoints.
 */
public final class LegacyQueryParameters {

	private final Map<String, ParsedValue> values;

	private LegacyQueryParameters(Map<String, ParsedValue> values) {
		this.values = values;
	}

	/** Parse an HTTP query string using PHP's ordered scalar/append-array precedence. */
	public static LegacyQueryParameters parse(String rawQuery) {
		Map<String, ParsedValue> parsed = new LinkedHashMap<>();
		if (rawQuery == null || rawQuery.isEmpty()) {
			return new LegacyQueryParameters(parsed);
		}

		for (String component : rawQuery.split("&", -1)) {
			int equals = component.indexOf('=');
			String encodedName = equals < 0 ? component : component.substring(0, equals);
			String encodedValue = equals < 0 ? "" : component.substring(equals + 1);
			String name = decode(encodedName);
			String value = decode(encodedValue);

			if (name.endsWith("[]") && name.length() > 2) {
				String baseName = name.substring(0, name.length() - 2);
				ParsedValue existing = parsed.get(baseName);
				List<String> items = existing != null && existing.array()
						? new ArrayList<>(existing.arrayValues()) : new ArrayList<>();
				items.add(value);
				parsed.put(baseName, ParsedValue.array(items));
			} else if (!name.isEmpty()) {
				parsed.put(name, ParsedValue.scalar(value));
			}
		}
		return new LegacyQueryParameters(parsed);
	}

	/**
	 * Return the PHP-parsed request value: {@link String} for a scalar, {@link List} for an append
	 * array, or {@code null} when the parameter is absent.
	 */
	public Object value(String name) {
		ParsedValue parsed = values.get(name);
		return parsed == null ? null : parsed.value();
	}

	private static String decode(String value) {
		return URLDecoder.decode(value, StandardCharsets.UTF_8);
	}

	private record ParsedValue(Object value, boolean array) {

		private static ParsedValue scalar(String value) {
			return new ParsedValue(value, false);
		}

		private static ParsedValue array(List<String> values) {
			return new ParsedValue(List.copyOf(values), true);
		}

		@SuppressWarnings("unchecked")
		private List<String> arrayValues() {
			return (List<String>) value;
		}
	}
}
