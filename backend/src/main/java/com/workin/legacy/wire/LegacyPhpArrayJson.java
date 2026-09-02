package com.workin.legacy.wire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonSerialize;

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
 * An empty map is answered as {@link #EMPTY_OBJECT}, matching
 * {@code (object)[]}: PHP's own empty array would encode as {@code []} -- which
 * is what {@link LegacyPhpEmptyArrayJsonConfig} makes every other empty map on
 * this surface do -- and the endpoints that care already cast around that.
 */
public final class LegacyPhpArrayJson {

	private LegacyPhpArrayJson() {
	}

	/**
	 * PHP's {@code (object)[]}: an empty structure that must reach the client
	 * as <code>{}</code> rather than {@code []}.
	 *
	 * <p>It carries its own serializer rather than relying on
	 * {@link LegacyPhpEmptyArrayJsonConfig} being registered, because that
	 * module is profile-scoped and this value's shape is not negotiable on any
	 * profile: an empty bean would otherwise fail to serialise at all.
	 */
	@JsonSerialize(using = PhpEmptyObject.Json.class)
	public static final class PhpEmptyObject {

		private PhpEmptyObject() {
		}

		static final class Json extends ValueSerializer<PhpEmptyObject> {

			@Override
			public void serialize(
					PhpEmptyObject value, JsonGenerator generator, SerializationContext context) {
				generator.writeStartObject();
				generator.writeEndObject();
			}

		}

	}

	/** The single instance of {@code (object)[]}; it carries no state. */
	public static final PhpEmptyObject EMPTY_OBJECT = new PhpEmptyObject();

	/**
	 * The map as PHP would encode it: the same map when its keys are not a
	 * {@code 0..n-1} integer sequence, or a {@link List} of its values when they
	 * are.
	 *
	 * @param map insertion-ordered; the order is what decides the answer
	 */
	public static Object encode(Map<String, Object> map) {
		if (map == null) {
			return null;
		}
		if (map.isEmpty()) {
			// `(object)[]` -- an empty result stays an object. PHP's bare empty
			// array would be `[]`, which is the divergence those casts exist to
			// close, so honouring the cast is the faithful choice here. Handing
			// the map back would no longer do it: an empty Map now renders as
			// `[]` like every other one.
			return EMPTY_OBJECT;
		}
		// `long`, not `int`: isCanonicalInteger() admits PHP's full signed
		// 64-bit key range, so parsing back with Integer.parseInt() would throw
		// on a key it had just accepted -- a department named `2147483648` is a
		// legal map key here and would 500 instead of encoding.
		long expected = 0;
		for (String key : map.keySet()) {
			if (!isCanonicalInteger(key) || Long.parseLong(key) != expected) {
				return map;
			}
			expected++;
		}
		return new ArrayList<>(map.values());
	}

	/**
	 * The same rule for a site whose PHP builds a <b>bare</b> array rather than
	 * one cast with {@code (object)}.
	 *
	 * <p>The only difference is the empty case, and it is the case that
	 * matters: {@link #encode} keeps an empty map an object because the
	 * endpoints it serves write {@code (object)[]}, whereas a bare
	 * {@code $map = []} that never gains a key encodes as {@code []}.
	 * {@code company_settings/options.php} is the second kind -- it builds
	 * {@code $map} in a loop over the definitions and passes it straight to
	 * {@code ok()}, so an empty catalogue answers a JSON array.
	 */
	public static Object encodeBareArray(Map<String, Object> map) {
		if (map == null || map.isEmpty()) {
			return List.of();
		}
		return encode(map);
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
