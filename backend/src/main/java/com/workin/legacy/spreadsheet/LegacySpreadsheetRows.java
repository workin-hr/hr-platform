package com.workin.legacy.spreadsheet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code spreadsheet_normalize_header_row()} and {@code spreadsheet_assoc_row()}
 * ({@code hr-legacy/apis/helpers/xlsx_parser.php:15-52}) -- the two helpers
 * every spreadsheet reader in legacy shares.
 *
 * <p>Wave 12.4's employee flow carries its own copies because it normalizes
 * header labels to a private canonical vocabulary; the attendance import does
 * not, and uses these two functions literally. They live here rather than in
 * the attendance package because they are the shared helper, not an attendance
 * one -- but nothing existing is rewired onto them, so no delivered wave's
 * behaviour moves.
 */
public final class LegacySpreadsheetRows {

	private LegacySpreadsheetRows() {
	}

	/**
	 * {@code spreadsheet_normalize_header_row()}:
	 * {@code strtolower(trim(preg_replace('/\s+/', '_', $cell)))}, with a blank
	 * result replaced by {@code column_N} and repeats suffixed {@code _2},
	 * {@code _3}, ...
	 *
	 * <h2>The order of those three operations is the surprising part</h2>
	 * <p>Whitespace becomes underscores <b>before</b> the trim, and
	 * {@code trim()} does not strip underscores -- so a header cell of
	 * {@code "  Employee Code  "} normalizes to {@code _employee_code_}, keeping
	 * the leading and trailing underscore. {@code detectCol()}'s exact-match
	 * pass then misses it and only its {@code str_contains} pass finds it. That
	 * is legacy's behaviour and it is reproduced, not tidied.
	 *
	 * <p>{@code preg_replace('/\s+/', ...)} has no {@code /u} flag, so the class
	 * is the ASCII set {@code [ \t\n\r\f\v]} -- which is exactly what Java's
	 * {@code \s} matches, and notably <em>not</em> the Unicode spaces an Arabic
	 * header can contain.
	 *
	 * <p>{@code strtolower()} is byte-wise ASCII in PHP 8 and locale
	 * independent, so it leaves every non-ASCII byte alone. Java's
	 * {@code toLowerCase} would fold Arabic, Greek and Turkish letters that PHP
	 * does not touch, which would change which key an Arabic header lands on --
	 * hence the explicit ASCII fold below.
	 *
	 * <p>The de-duplication counter is keyed on the <em>original</em> key and the
	 * suffixed name is never itself recorded, so a sheet whose third column is
	 * literally named {@code name_2} can still collide with the second
	 * {@code name}. Faithful: PHP has the same hole.
	 */
	public static List<String> normalizeHeaderRow(List<String> header) {
		List<String> normalized = new ArrayList<>();
		Map<String, Integer> seen = new HashMap<>();
		if (header == null) {
			return normalized;
		}
		for (String cell : header) {
			String key = asciiToLower(phpTrimForHeader(collapseWhitespace(cell == null ? "" : cell)));
			if (key.isEmpty()) {
				key = "column_" + (normalized.size() + 1);
			}
			Integer count = seen.get(key);
			if (count != null) {
				int next = count + 1;
				seen.put(key, next);
				key = key + "_" + next;
			} else {
				seen.put(key, 1);
			}
			normalized.add(key);
		}
		return normalized;
	}

	/**
	 * {@code spreadsheet_assoc_row()}: the data row keyed by the header, padded
	 * with nulls when it is short and truncated when it is long.
	 *
	 * @return {@code null} for an empty header, exactly as PHP returns null
	 */
	public static Map<String, Object> assocRow(List<String> header, List<String> row) {
		if (header == null || header.isEmpty()) {
			return null;
		}
		Map<String, Object> combined = new LinkedHashMap<>();
		for (int index = 0; index < header.size(); index++) {
			// array_combine over a padded/truncated value list. A duplicate key
			// keeps the last value, which is what array_combine does too.
			combined.put(header.get(index), row != null && index < row.size() ? row.get(index) : null);
		}
		return combined;
	}

	/** {@code preg_replace('/\s+/', '_', $cell)} -- ASCII whitespace runs only. */
	private static String collapseWhitespace(String value) {
		StringBuilder out = new StringBuilder(value.length());
		int index = 0;
		while (index < value.length()) {
			if (isAsciiWhitespace(value.charAt(index))) {
				out.append('_');
				while (index < value.length() && isAsciiWhitespace(value.charAt(index))) {
					index++;
				}
			} else {
				out.append(value.charAt(index));
				index++;
			}
		}
		return out.toString();
	}

	private static boolean isAsciiWhitespace(char character) {
		return character == ' ' || character == '\t' || character == '\n'
				|| character == '\r' || character == '\f' || character == 0x0B;
	}

	/**
	 * {@code trim()}'s default charlist, applied after the whitespace run has
	 * already become underscores -- so in practice it only ever removes a NUL
	 * that survived the substitution.
	 */
	private static String phpTrimForHeader(String value) {
		return com.workin.legacy.LegacyValues.phpTrim(value);
	}

	/** {@code strtolower()}: ASCII {@code A-Z} only, every other byte untouched. */
	private static String asciiToLower(String value) {
		StringBuilder out = new StringBuilder(value.length());
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			out.append(character >= 'A' && character <= 'Z' ? (char) (character + 32) : character);
		}
		return out.toString();
	}

}
