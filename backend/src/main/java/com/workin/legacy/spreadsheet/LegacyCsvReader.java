package com.workin.legacy.spreadsheet;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The CSV half of {@code employee_excel_load_rows()}, with D-085's correction.
 *
 * <h2>What D-085 changes, and what it does not</h2>
 * <p>Legacy's BOM handling is inverted -- it discards three bytes when there is
 * <em>no</em> BOM and keeps the BOM when there is one, which shifts every row of
 * the template the application itself generates. This reader consumes the BOM
 * exactly once when present and never touches the bytes when it is absent.
 * Everything else about the reading is legacy's: the delimiter is chosen by
 * comparing commas against semicolons in the first physical line, and the
 * records are parsed the way {@code fgetcsv()} parses them.
 */
public final class LegacyCsvReader {

	private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

	private LegacyCsvReader() {
	}

	/** Reads every record, in file order. Rows are not padded or truncated here. */
	public static List<List<String>> read(byte[] content) {
		String text = new String(stripBom(content), StandardCharsets.UTF_8);
		return parse(text, detectDelimiter(text));
	}

	/**
	 * {@code $delimiter = substr_count($firstLine, ',') >= substr_count($firstLine, ';') ? ',' : ';'}
	 * -- comma wins ties, and only the first physical line is consulted.
	 */
	public static char detectDelimiter(String text) {
		int newline = text.indexOf('\n');
		String firstLine = newline < 0 ? text : text.substring(0, newline + 1);
		long commas = firstLine.chars().filter(character -> character == ',').count();
		long semicolons = firstLine.chars().filter(character -> character == ';').count();
		return commas >= semicolons ? ',' : ';';
	}

	/** D-085: the BOM is consumed once, and only when it is actually there. */
	public static byte[] stripBom(byte[] content) {
		if (content == null) {
			return new byte[0];
		}
		if (content.length >= BOM.length
				&& content[0] == BOM[0] && content[1] == BOM[1] && content[2] == BOM[2]) {
			byte[] withoutBom = new byte[content.length - BOM.length];
			System.arraycopy(content, BOM.length, withoutBom, 0, withoutBom.length);
			return withoutBom;
		}
		return content;
	}

	/**
	 * The record parser on its own, for a caller that has already decided the
	 * delimiter and positioned past whatever prefix it means to skip.
	 *
	 * <p>Exists because the attendance import's CSV branch is <em>not</em>
	 * D-085's corrected reader: that decision is scoped to
	 * {@code employee_excel_load_rows()}, and
	 * {@code attendance_import_load_rows()} keeps legacy's own inverted BOM
	 * handling. Both flows still parse records identically once the bytes are
	 * chosen, so the parser is shared and only the byte positioning differs.
	 */
	public static List<List<String>> parseRecords(String text, char delimiter) {
		return parse(text, delimiter);
	}

	/**
	 * {@code fgetcsv()} semantics: double quotes enclose a field, a doubled quote
	 * inside one is a literal quote, and a newline inside quotes stays part of
	 * the field -- which the template's multi-line headers depend on.
	 *
	 * <p>An enclosure can only <b>start</b> at the first character of a field.
	 * A quote reached after the field has already begun ({@code 12"3"}) is a
	 * literal character, exactly as {@code fgetcsv()} treats it -- it does not
	 * open an enclosure and swallow the digits that follow.
	 */
	private static List<List<String>> parse(String text, char delimiter) {
		List<List<String>> records = new ArrayList<>();
		List<String> record = new ArrayList<>();
		StringBuilder field = new StringBuilder();
		boolean quoted = false;
		boolean sawAnything = false;

		for (int index = 0; index < text.length(); index++) {
			char character = text.charAt(index);
			if (quoted) {
				if (character == '"') {
					if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
						field.append('"');
						index++;
					} else {
						quoted = false;
					}
				} else {
					field.append(character);
				}
				continue;
			}
			if (character == '"' && field.length() == 0) {
				quoted = true;
				sawAnything = true;
			} else if (character == delimiter) {
				record.add(field.toString());
				field.setLength(0);
				sawAnything = true;
			} else if (character == '\n' || character == '\r') {
				if (character == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
					index++;
				}
				record.add(field.toString());
				field.setLength(0);
				records.add(record);
				record = new ArrayList<>();
				sawAnything = false;
			} else {
				field.append(character);
				sawAnything = true;
			}
		}
		if (sawAnything || field.length() > 0 || !record.isEmpty()) {
			record.add(field.toString());
			records.add(record);
		}
		return records;
	}

}
