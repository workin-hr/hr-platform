package com.workin.legacy.wire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PHP's array-to-JSON rule, for the response maps built by keying on a
 * caller-controlled string.
 *
 * <h2>The rule</h2>
 * <p>PHP has one array type. {@code json_encode()} emits it as a JSON
 * <b>array</b> when its keys are exactly the integers {@code 0..n-1} in order,
 * and as a JSON <b>object</b> otherwise. Two things feed that decision:
 *
 * <ul>
 * <li>a string key that is a <em>canonical decimal integer</em> is silently
 *     converted to an int on assignment -- {@code $a["0"]} and {@code $a[0]}
 *     are the same key, while {@code $a["00"]}, {@code $a["0.0"]} and
 *     {@code $a[" 0"]} stay strings;</li>
 * <li>insertion order, not sorted order, decides whether the resulting integer
 *     keys form the sequence.</li>
 * </ul>
 *
 * <h2>Why a response needs it</h2>
 * <p>{@code dashboard/stats.php} keys several maps by <em>department or branch
 * name</em>. A company with one department named {@code "0"} therefore produces
 * an array with the single key {@code 0}, and {@code salaries_by_department}
 * arrives as {@code [1234]} rather than {@code {"0":1234}} -- a change of
 * <b>type</b>, which is precisely what the surrounding {@code (object)[]} casts
 * exist to prevent for the empty case.
 *
 * <p>A Java {@code Map} always serialises as an object, so without this the
 * port answers a different shape for that data. Applied at the point a map is
 * handed to the wire, it converts only the maps PHP would have converted.
 *
 * <h2>What it deliberately does not do</h2>
 * <p>It does not touch nested values it is not given, and it does not reorder.
 * An empty map stays an object, matching {@code (object)[]}: PHP's own empty
 * array would encode as {@code []}, and the endpoints that care already cast
 * around that.
 */
public final class LegacyPhpArrayJson {

	private LegacyPhpArrayJson() {
	}

	/**
	 * The map as PHP would encode it: the same map when its keys are not a
	 * {@code 0..n-1} integer sequence, or a {@link List} of its values when they
	 * are.
	 *
	 * @param map insertion-ordered; the order is what decides the answer
	 */
	public static Object encode(Map<String, Object> map) {
		if (map == null || map.isEmpty()) {
			// `(object)[]` -- an empty result stays an object. PHP's bare empty
			// array would be `[]`, which is the divergence those casts exist to
			// close, so honouring the cast is the faithful choice here.
			return map;
		}
		int expected = 0;
		for (String key : map.keySet()) {
			if (!isCanonicalInteger(key) || Integer.parseInt(key) != expected) {
				return map;
			}
			expected++;
		}
		return new ArrayList<>(map.values());
	}

	/** Every map value of {@code out}, encoded by {@link #encode}. */
	public static Map<String, Object> encodeValues(Map<String, Object> out, String... keys) {
		Map<String, Object> result = new LinkedHashMap<>(out);
		for (String key : keys) {
			Object value = result.get(key);
			if (value instanceof Map<?, ?> map) {
				@SuppressWarnings("unchecked")
				Map<String, Object> typed = (Map<String, Object>) map;
				result.put(key, encode(typed));
			}
		}
		return result;
	}

	/**
	 * Whether PHP would convert this string key to an integer.
	 *
	 * <p>Only a canonical decimal integer converts: optional {@code -}, then
	 * either {@code 0} alone or a non-zero leading digit. So {@code "0"} and
	 * {@code "-12"} convert, while {@code "00"}, {@code "01"}, {@code "+1"},
	 * {@code " 1"}, {@code "1.0"}, {@code "-0"} and anything exceeding the
	 * platform integer range stay string keys.
	 *
	 * <p>{@code "-0"} is the trap: it looks canonical and is not, because PHP
	 * canonicalises the integer {@code 0} back to {@code "0"} and the two would
	 * no longer round-trip. This repository has met it before -- see
	 * {@code LegacyQueryParameters#phpArrayKey}.
	 */
	static boolean isCanonicalInteger(String key) {
		if (key == null || key.isEmpty() || "-0".equals(key)) {
			return false;
		}
		int start = key.charAt(0) == '-' ? 1 : 0;
		if (start == key.length()) {
			return false;
		}
		if (key.charAt(start) == '0' && key.length() - start > 1) {
			return false;
		}
		for (int i = start; i < key.length(); i++) {
			if (key.charAt(i) < '0' || key.charAt(i) > '9') {
				return false;
			}
		}
		try {
			Long.parseLong(key);
		} catch (NumberFormatException ex) {
			return false;
		}
		return true;
	}
}
