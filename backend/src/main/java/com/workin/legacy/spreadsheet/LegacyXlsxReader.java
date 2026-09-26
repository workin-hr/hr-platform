package com.workin.legacy.spreadsheet;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * {@code XlsxParser} ({@code hr-legacy/apis/helpers/xlsx_parser.php:132-330}),
 * on the JDK's own ZIP and XML support.
 *
 * <p>Only the surface the employee spreadsheet endpoints exercise: shared
 * strings, inline strings, numeric and boolean cells, the {@code numFmtId}
 * range that marks a date, the first worksheet as the workbook relationships
 * resolve it, and sparse cells filled out to a contiguous row. Not a
 * general-purpose Excel reader, deliberately -- a full library would bring its
 * own date, blank-cell and coercion behaviour, which is exactly what this port
 * exists to avoid.
 */
public final class LegacyXlsxReader {

	/** {@code isDateFormat()}: 14-22 and 45-47 are the built-in date and time formats. */
	private static boolean isDateFormat(int numberFormatId) {
		return (numberFormatId >= 14 && numberFormatId <= 22) || (numberFormatId >= 45 && numberFormatId <= 47);
	}

	private LegacyXlsxReader() {
	}

	/** Thrown when the container or its parts cannot be read -- never swallowed into a CSV re-read (D-085). */
	public static class LegacyXlsxException extends RuntimeException {

		public LegacyXlsxException(String message) {
			super(message);
		}

		public LegacyXlsxException(String message, Throwable cause) {
			super(message, cause);
		}

	}

	/**
	 * Reads the first worksheet as a row-major matrix, exactly as
	 * {@code parse(false)} does: no header handling, sparse cells filled with
	 * nulls, rows ordered by their {@code r} attribute.
	 */
	public static List<List<String>> readFirstSheet(byte[] content) {
		Map<String, byte[]> parts = readZip(content);

		List<String> sharedStrings = parts.containsKey("xl/sharedStrings.xml")
				? parseSharedStrings(parts.get("xl/sharedStrings.xml"))
				: List.of();
		List<Integer> styles = parts.containsKey("xl/styles.xml")
				? parseStyles(parts.get("xl/styles.xml"))
				: List.of();

		String sheetPath = firstWorksheetPath(parts.get("xl/_rels/workbook.xml.rels"));
		byte[] sheetXml = parts.get(sheetPath);
		if (sheetXml == null) {
			throw new LegacyXlsxException("Cannot read sheet XML");
		}
		return parseSheet(sheetXml, sharedStrings, styles);
	}

	/**
	 * The most any one part may inflate to. An exported attendance or employee
	 * sheet is a few megabytes of XML at most; a part past this is not a
	 * workbook anyone uploads, it is a decompression bomb (D-289).
	 */
	static final int MAX_PART_BYTES = 32 * 1024 * 1024;

	/** The most every part together may inflate to. */
	static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;

	/** The most entries the container may hold; a workbook has a few dozen. */
	static final int MAX_ENTRIES = 2000;

	/** Excel's own last column, {@code XFD}. A cell reference past it cannot come from a workbook. */
	static final int MAX_COLUMN_INDEX = 16_383;

	/**
	 * The most cells one sheet may cost, counted twice over: every {@code c}
	 * element read, and every slot of every row once it is padded out to its
	 * last column. The column cap bounds one row, not the sheet -- forty
	 * thousand rows each naming {@code XFD} are a 190 KB upload and 655 million
	 * slots (D-289). Two million is far past an attendance export or an
	 * employee sheet, which the 1 MB upload limit already keeps to a few
	 * hundred thousand cells. Also the most shared strings or cell formats a
	 * workbook may declare, since a sheet within this bound cannot use more.
	 */
	static final int MAX_TOTAL_CELLS = 2_000_000;

	/** The most {@code row} elements one sheet may hold; Excel's own limit is 1,048,576. */
	static final int MAX_ROWS = 200_000;

	/**
	 * Every part, inflated through a bounded read. PHP's {@code ZipArchive}
	 * has no limit; a file past one of the three bounds is refused as an
	 * unreadable workbook, which is what the callers already answer for a
	 * corrupt one.
	 */
	private static Map<String, byte[]> readZip(byte[] content) {
		Map<String, byte[]> parts = new LinkedHashMap<>();
		long total = 0;
		int entries = 0;
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (++entries > MAX_ENTRIES) {
					throw new LegacyXlsxException("XLSX file has too many parts");
				}
				if (!entry.isDirectory()) {
					byte[] part = readBounded(zip, (int) Math.min(MAX_PART_BYTES, MAX_TOTAL_BYTES - total));
					total += part.length;
					parts.put(entry.getName(), part);
				}
				zip.closeEntry();
			}
		} catch (IOException ex) {
			throw new LegacyXlsxException("Cannot open XLSX file", ex);
		}
		if (parts.isEmpty()) {
			throw new LegacyXlsxException("Cannot open XLSX file");
		}
		return parts;
	}

	/** Reads the current entry, refusing it as soon as it passes {@code limit} -- never after. */
	private static byte[] readBounded(InputStream entry, int limit) throws IOException {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int read;
		while ((read = entry.read(buffer)) != -1) {
			if (out.size() + read > limit) {
				throw new LegacyXlsxException("XLSX part inflates past the size limit");
			}
			out.write(buffer, 0, read);
		}
		return out.toByteArray();
	}

	/**
	 * {@code parseSharedStrings()}: each {@code si} is the concatenation of its
	 * {@code t} nodes. Read as a stream, like every part here: a DOM of a part
	 * this size costs many times the part (D-289).
	 */
	private static List<String> parseSharedStrings(byte[] xml) {
		List<String> strings = new ArrayList<>();
		StringBuilder item = null;
		StringBuilder text = null;
		int itemDepth = 0;
		int textDepth = 0;
		int depth = 0;
		XMLStreamReader reader = open(xml);
		try {
			while (reader.hasNext()) {
				switch (next(reader)) {
					case XMLStreamConstants.START_ELEMENT -> {
						depth++;
						String name = reader.getLocalName();
						if (item == null && "si".equals(name)) {
							if (strings.size() >= MAX_TOTAL_CELLS) {
								throw new LegacyXlsxException("XLSX file has too many shared strings");
							}
							item = new StringBuilder();
							itemDepth = depth;
						} else if (item != null && text == null && "t".equals(name)) {
							text = new StringBuilder();
							textDepth = depth;
						}
					}
					case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
						if (text != null) {
							text.append(reader.getText());
						}
					}
					case XMLStreamConstants.END_ELEMENT -> {
						if (text != null && depth == textDepth) {
							item.append(text);
							text = null;
						} else if (item != null && depth == itemDepth) {
							strings.add(item.toString());
							item = null;
						}
						depth--;
					}
					default -> {
					}
				}
			}
		} catch (XMLStreamException ex) {
			throw unreadable(xml, ex);
		}
		return strings;
	}

	/** {@code parseStyles()}: the {@code numFmtId} of every {@code xf} under the first {@code cellXfs}, in order. */
	private static List<Integer> parseStyles(byte[] xml) {
		List<Integer> styles = new ArrayList<>();
		boolean seen = false;
		int formatsDepth = -1;
		int depth = 0;
		XMLStreamReader reader = open(xml);
		try {
			while (reader.hasNext()) {
				switch (next(reader)) {
					case XMLStreamConstants.START_ELEMENT -> {
						depth++;
						String name = reader.getLocalName();
						if (!seen && "cellXfs".equals(name)) {
							seen = true;
							formatsDepth = depth;
						} else if (formatsDepth > 0 && "xf".equals(name)) {
							if (styles.size() >= MAX_TOTAL_CELLS) {
								throw new LegacyXlsxException("XLSX file has too many cell formats");
							}
							String numberFormatId = attribute(reader, "numFmtId");
							styles.add(numberFormatId.isEmpty() ? 0 : Integer.parseInt(numberFormatId));
						}
					}
					case XMLStreamConstants.END_ELEMENT -> {
						if (depth == formatsDepth) {
							formatsDepth = -1;
						}
						depth--;
					}
					default -> {
					}
				}
			}
		} catch (XMLStreamException ex) {
			throw unreadable(xml, ex);
		}
		return styles;
	}

	/**
	 * {@code parseWorkbookRels()}: the worksheet relationships, ordered by the
	 * digits in their {@code Id}, with {@code worksheets/sheet1.xml} as the
	 * fallback when the part is missing entirely.
	 */
	private static String firstWorksheetPath(byte[] relsXml) {
		if (relsXml == null) {
			return "xl/worksheets/sheet1.xml";
		}
		TreeMap<Integer, String> sheets = new TreeMap<>();
		int worksheets = 0;
		XMLStreamReader reader = open(relsXml);
		try {
			while (reader.hasNext()) {
				if (next(reader) != XMLStreamConstants.START_ELEMENT
						|| !"Relationship".equals(reader.getLocalName())
						|| !attribute(reader, "Type").contains("worksheet")) {
					continue;
				}
				// Every worksheet is a part of its own, so more of them than
				// the container may hold entries cannot name real sheets.
				if (++worksheets > MAX_ENTRIES) {
					throw new LegacyXlsxException("XLSX file has too many parts");
				}
				String digits = attribute(reader, "Id").replaceAll("\\D", "");
				int order = digits.isEmpty() ? 1 : Integer.parseInt(digits);
				String target = attribute(reader, "Target");
				sheets.put(order - 1, target.startsWith("/") ? target.substring(1) : target);
			}
		} catch (XMLStreamException ex) {
			throw unreadable(relsXml, ex);
		}
		if (sheets.isEmpty()) {
			throw new LegacyXlsxException("Sheet 0 not found");
		}
		return "xl/" + sheets.firstEntry().getValue();
	}

	/**
	 * The worksheet, streamed. Every bound is checked before what it bounds is
	 * allocated: a row's cells are counted as they arrive, and its padded width
	 * is charged before the padded row is built.
	 */
	private static List<List<String>> parseSheet(byte[] xml, List<String> sharedStrings, List<Integer> styles) {
		TreeMap<Integer, List<String>> rows = new TreeMap<>();
		int rowCount = 0;
		long cellCount = 0;
		long paddedCells = 0;
		int depth = 0;

		Map<Integer, String> cells = null;
		int rowIndex = 0;
		int rowDepth = 0;
		int rowCells = 0;

		boolean inCell = false;
		int cellDepth = 0;
		int column = 0;
		String type = "";
		String style = "";
		StringBuilder text = null;
		int textDepth = 0;
		String firstText = null;
		StringBuilder value = null;
		int valueDepth = 0;
		String firstValue = null;

		XMLStreamReader reader = open(xml);
		try {
			while (reader.hasNext()) {
				switch (next(reader)) {
					case XMLStreamConstants.START_ELEMENT -> {
						depth++;
						String name = reader.getLocalName();
						if (cells == null) {
							if ("row".equals(name)) {
								if (++rowCount > MAX_ROWS) {
									throw new LegacyXlsxException("XLSX sheet has too many rows");
								}
								rowIndex = Integer.parseInt(attribute(reader, "r")) - 1;
								cells = new HashMap<>();
								rowDepth = depth;
								rowCells = 0;
							}
						} else if (!inCell) {
							if ("c".equals(name)) {
								// A row holds at most one cell per column; more
								// is a file written to cost memory, not a sheet.
								if (++rowCells > MAX_COLUMN_INDEX + 1 || ++cellCount > MAX_TOTAL_CELLS) {
									throw new LegacyXlsxException("XLSX sheet has too many cells");
								}
								column = columnIndex(withoutDigits(attribute(reader, "r")));
								if (column > MAX_COLUMN_INDEX) {
									// The row below is filled out to its last column, so an
									// invented reference like ZZZZZZ1 would allocate millions.
									throw new LegacyXlsxException("XLSX cell reference out of range");
								}
								type = attribute(reader, "t");
								style = attribute(reader, "s");
								inCell = true;
								cellDepth = depth;
								firstText = null;
								firstValue = null;
							}
						} else {
							// The first t and the first v anywhere inside the
							// cell, as getElementsByTagName() found them.
							if (firstText == null && text == null && "t".equals(name)) {
								text = new StringBuilder();
								textDepth = depth;
							}
							if (firstValue == null && value == null && "v".equals(name)) {
								value = new StringBuilder();
								valueDepth = depth;
							}
						}
					}
					case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
						if (text != null) {
							text.append(reader.getText());
						}
						if (value != null) {
							value.append(reader.getText());
						}
					}
					case XMLStreamConstants.END_ELEMENT -> {
						if (text != null && depth == textDepth) {
							firstText = text.toString();
							text = null;
						}
						if (value != null && depth == valueDepth) {
							firstValue = value.toString();
							value = null;
						}
						if (inCell && depth == cellDepth) {
							cells.put(column, cellValue(type, style, firstText, firstValue, sharedStrings, styles));
							inCell = false;
						} else if (cells != null && !inCell && depth == rowDepth) {
							if (!cells.isEmpty()) {
								int lastColumn = cells.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
								// Charged before the padded row exists: forty thousand
								// one-cell rows at XFD are a few hundred KB of file
								// and would otherwise be 655 million list slots.
								paddedCells += lastColumn + 1;
								if (paddedCells > MAX_TOTAL_CELLS) {
									throw new LegacyXlsxException("XLSX sheet has too many cells");
								}
								List<String> row = new ArrayList<>(lastColumn + 1);
								for (int index = 0; index <= lastColumn; index++) {
									// Sparse cells become nulls so the row is contiguous.
									row.add(cells.get(index));
								}
								rows.put(rowIndex, row);
							}
							cells = null;
						}
						depth--;
					}
					default -> {
					}
				}
			}
		} catch (XMLStreamException ex) {
			throw unreadable(xml, ex);
		}
		return new ArrayList<>(rows.values());
	}

	/**
	 * One cell's value from its {@code t} and {@code s} attributes and the text
	 * of its first {@code t} and first {@code v} descendants, {@code null} when
	 * there was none.
	 */
	private static String cellValue(String type, String styleAttribute, String firstText, String firstValue,
			List<String> sharedStrings, List<Integer> styles) {
		if ("inlineStr".equals(type)) {
			return firstText == null ? "" : firstText;
		}
		if (firstValue == null) {
			return null;
		}
		String value = firstValue;
		if ("s".equals(type)) {
			int position = Integer.parseInt(value);
			return position >= 0 && position < sharedStrings.size() ? sharedStrings.get(position) : "";
		}
		if ("b".equals(type)) {
			return "1".equals(value) ? "TRUE" : "FALSE";
		}
		if (type.isEmpty() || "n".equals(type)) {
			int style = styleAttribute.isEmpty() ? 0 : Integer.parseInt(styleAttribute);
			int numberFormatId = style >= 0 && style < styles.size() ? styles.get(style) : 0;
			if (isDateFormat(numberFormatId) && isNumeric(value)) {
				return excelSerialToDateTime(Double.parseDouble(value));
			}
		}
		return value;
	}

	/** The reference with its ASCII digits removed, without compiling a pattern for every cell. */
	private static String withoutDigits(String reference) {
		StringBuilder letters = new StringBuilder(reference.length());
		for (int index = 0; index < reference.length(); index++) {
			char character = reference.charAt(index);
			if (character < '0' || character > '9') {
				letters.append(character);
			}
		}
		return letters.toString();
	}

	/** {@code colToIndex()}: A becomes 0, AA becomes 26. */
	static int columnIndex(String letters) {
		int index = 0;
		for (char letter : letters.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
			index = index * 26 + (letter - 'A' + 1);
		}
		return index - 1;
	}

	private static boolean isNumeric(String value) {
		try {
			Double.parseDouble(value);
			return true;
		} catch (NumberFormatException ex) {
			return false;
		}
	}

	/**
	 * {@code excel_serial_to_datetime_string()}
	 * ({@code hr-legacy/apis/helpers/xlsx_parser.php}), which is deliberately
	 * naive: the whole part is days since the 1899-12-30 epoch that serial 25569
	 * pins to 1970-01-01, the fraction is a time of day, and there is <em>no</em>
	 * correction for Excel's phantom 29 February 1900. Serials below 60
	 * therefore land a day earlier than a leap-aware conversion would put them,
	 * and that is the behaviour the analyzer sees.
	 *
	 * <p>The format is conditional in the same way: a whole-day serial returns
	 * {@code Y-m-d} with no time at all, and only a fractional one returns
	 * {@code Y-m-d H:i:s}. The conversion is in UTC ({@code gmdate}), so a
	 * server timezone never shifts a punch time.
	 */
	public static String excelSerialToDateTime(double serial) {
		// (int) $serial truncates toward zero, as a Java long cast does.
		long days = (long) serial;
		double time = serial - days;
		long unixSeconds = (days - 25_569L) * 86_400L + Math.round(time * 86_400d);
		LocalDateTime moment = LocalDateTime.ofEpochSecond(unixSeconds, 0, java.time.ZoneOffset.UTC);
		return moment.format(time > 0
				? DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
				: DateTimeFormatter.ofPattern("yyyy-MM-dd"));
	}

	/**
	 * A streaming reader over one part. Not namespace-aware, as the DOM this
	 * replaced was not, so an element's name is its qualified name and a
	 * prefixed {@code x:row} is not a {@code row}.
	 */
	private static XMLStreamReader open(byte[] xml) {
		XMLInputFactory factory = XMLInputFactory.newFactory();
		factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
		// Spreadsheet parts are untrusted input: no DTD and no external entities, ever.
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
		factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
		try {
			return factory.createXMLStreamReader(new ByteArrayInputStream(xml));
		} catch (XMLStreamException ex) {
			throw unreadable(xml, ex);
		}
	}

	/** The next event, refusing a {@code DOCTYPE} as the DOM parser's {@code disallow-doctype-decl} did. */
	private static int next(XMLStreamReader reader) throws XMLStreamException {
		int event = reader.next();
		if (event == XMLStreamConstants.DTD) {
			throw new XMLStreamException("DOCTYPE is disallowed");
		}
		return event;
	}

	/** {@code getAttribute()}: the unprefixed attribute's value, or the empty string. */
	private static String attribute(XMLStreamReader reader, String name) {
		for (int index = 0; index < reader.getAttributeCount(); index++) {
			String prefix = reader.getAttributePrefix(index);
			if ((prefix == null || prefix.isEmpty()) && name.equals(reader.getAttributeLocalName(index))) {
				return reader.getAttributeValue(index);
			}
		}
		return "";
	}

	private static LegacyXlsxException unreadable(byte[] xml, Exception cause) {
		return new LegacyXlsxException("Cannot read XLSX part: " + new String(xml, 0,
				Math.min(xml.length, 32), StandardCharsets.UTF_8), cause);
	}

}
