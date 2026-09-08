package com.workin.backend.platformadmin.settings;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

import com.workin.legacy.LegacyValues;

/**
 * {@code configs_normalize_value()} and {@code configs_input_value()}.
 *
 * <p>Separate from the store because these are pure value rules, and because
 * two of them are asymmetric in a way worth testing directly rather than
 * through a page.
 */
public final class ConfigValues {

	private ConfigValues() {
	}

	/** The strings {@code configs_input_value()} reads back as "on". */
	private static final List<String> TRUTHY_STORED = List.of("1", "true", "yes");

	/** The strings {@code configs_normalize_value()} accepts as "on" from a form. */
	private static final List<String> TRUTHY_POSTED = List.of("true", "yes", "on");

	/**
	 * {@code configs_normalize_value()}: what gets stored.
	 *
	 * <p>A boolean is written as the <em>word</em> {@code "true"} or
	 * {@code "false"}, not {@code 1}/{@code 0}. That matters because
	 * {@link #forInput} reads {@code "1"} back as on too -- the two are
	 * deliberately asymmetric, so rows written before this format still
	 * display correctly.
	 */
	public static String normalize(String type, String raw) {
		String value = raw == null ? "" : raw;
		return switch (type) {
			case "boolean" -> boolish(value) ? "true" : "false";
			// PHP's (int) cast, then floored at zero: a negative build number
			// stores as 0, and "12abc" stores as 12.
			case "number" -> String.valueOf(Math.max(0, LegacyValues.toPhpLong(value)));
			// Trimmed and stored as-is. Legacy validates nothing here, so
			// neither does this; "not a url" is a url as far as the page cares.
			case "url" -> value.trim();
			case "date" -> date(value.trim());
			default -> value.trim();
		};
	}

	private static boolean boolish(String raw) {
		return "1".equals(raw) || TRUTHY_POSTED.contains(raw.toLowerCase(Locale.ROOT));
	}

	/**
	 * {@code Y-m-d} or {@code Y-n-j}, normalised to {@code Y-m-d}; anything
	 * else stores as empty.
	 *
	 * <p>PHP's {@code !} prefix resets the unspecified fields, so a bare date
	 * never picks up today's time. {@code Y-n-j} is the unpadded form, which is
	 * why {@code 2026-9-6} is accepted and {@code 2026/09/06} is not.
	 */
	private static String date(String raw) {
		if (raw.isEmpty()) {
			return "";
		}
		for (DateTimeFormatter format : List.of(
				DateTimeFormatter.ofPattern("yyyy-MM-dd"),
				DateTimeFormatter.ofPattern("yyyy-M-d"))) {
			try {
				return LocalDate.parse(raw, format).toString();
			}
			catch (DateTimeParseException ignored) {
				// Try the next shape, then give up as legacy does.
			}
		}
		return "";
	}

	/**
	 * {@code configs_input_value()}: what the form shows.
	 *
	 * <p>Only booleans are translated, and they accept {@code "1"} as well as
	 * {@code "true"} and {@code "yes"} -- see {@link #normalize} for why the
	 * pair does not round-trip through one vocabulary.
	 */
	public static String forInput(String type, String stored) {
		String value = stored == null ? "" : stored;
		if ("boolean".equals(type)) {
			return TRUTHY_STORED.contains(value.toLowerCase(Locale.ROOT)) ? "1" : "0";
		}
		return value;
	}

	/** Whether a stored boolean should render its control as checked. */
	public static boolean isOn(String stored) {
		return "1".equals(forInput("boolean", stored));
	}

}
