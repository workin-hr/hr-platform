package com.workin.legacy.spreadsheet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * PHP's {@code fputcsv()} with its default arguments, which is how every CSV
 * this module hands out is written.
 *
 * <p>The quoting rule is the one that matters and the one that is easy to get
 * wrong: PHP encloses a field when it contains the delimiter, the enclosure,
 * the escape character, a newline, a carriage return, a tab <em>or a space</em>.
 * That last one is why the employee template's Arabic column headers arrive
 * quoted while the single-word salary group labels do not, and any writer that
 * quotes on a different rule produces a different file for the same data.
 */
public final class LegacyCsvWriter {

	/** {@code fprintf($output, chr(0xEF) . chr(0xBB) . chr(0xBF))}. */
	public static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

	private static final char DELIMITER = ',';

	private static final char ENCLOSURE = '"';

	/** {@code fputcsv()}'s default {@code $escape}, which counts towards enclosing. */
	private static final char ESCAPE = '\\';

	private LegacyCsvWriter() {
	}

	/** A BOM, then one record per row, each terminated by {@code "\n"}. */
	public static byte[] writeWithBom(List<List<String>> records) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try {
			bytes.write(BOM);
			for (List<String> record : records) {
				bytes.write(record(record).getBytes(StandardCharsets.UTF_8));
			}
		} catch (IOException ex) {
			// ByteArrayOutputStream does not throw; this keeps the checked type honest.
			throw new IllegalStateException(ex);
		}
		return bytes.toByteArray();
	}

	/** One {@code fputcsv()} call: the fields, joined, then {@code "\n"}. */
	public static String record(List<String> fields) {
		StringBuilder line = new StringBuilder();
		for (int index = 0; index < fields.size(); index++) {
			if (index > 0) {
				line.append(DELIMITER);
			}
			line.append(field(fields.get(index) == null ? "" : fields.get(index)));
		}
		return line.append('\n').toString();
	}

	private static String field(String value) {
		if (!needsEnclosure(value)) {
			return value;
		}
		StringBuilder enclosed = new StringBuilder().append(ENCLOSURE);
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (character == ENCLOSURE) {
				// Inside an enclosure the enclosure character is doubled.
				enclosed.append(ENCLOSURE);
			}
			enclosed.append(character);
		}
		return enclosed.append(ENCLOSURE).toString();
	}

	private static boolean needsEnclosure(String value) {
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (character == DELIMITER || character == ENCLOSURE || character == ESCAPE
					|| character == '\n' || character == '\r' || character == '\t' || character == ' ') {
				return true;
			}
		}
		return false;
	}

}
