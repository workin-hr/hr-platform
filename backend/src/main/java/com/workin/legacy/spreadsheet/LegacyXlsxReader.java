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

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

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

	private static Map<String, byte[]> readZip(byte[] content) {
		Map<String, byte[]> parts = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (!entry.isDirectory()) {
					parts.put(entry.getName(), zip.readAllBytes());
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

	/** {@code parseSharedStrings()}: each {@code si} is the concatenation of its {@code t} nodes. */
	private static List<String> parseSharedStrings(byte[] xml) {
		List<String> strings = new ArrayList<>();
		NodeList items = parse(xml).getElementsByTagName("si");
		for (int index = 0; index < items.getLength(); index++) {
			StringBuilder text = new StringBuilder();
			NodeList texts = ((Element) items.item(index)).getElementsByTagName("t");
			for (int textIndex = 0; textIndex < texts.getLength(); textIndex++) {
				text.append(texts.item(textIndex).getTextContent());
			}
			strings.add(text.toString());
		}
		return strings;
	}

	/** {@code parseStyles()}: the {@code numFmtId} of every {@code xf} under {@code cellXfs}, in order. */
	private static List<Integer> parseStyles(byte[] xml) {
		List<Integer> styles = new ArrayList<>();
		NodeList cellXfs = parse(xml).getElementsByTagName("cellXfs");
		if (cellXfs.getLength() == 0) {
			return styles;
		}
		NodeList formats = ((Element) cellXfs.item(0)).getElementsByTagName("xf");
		for (int index = 0; index < formats.getLength(); index++) {
			String numberFormatId = ((Element) formats.item(index)).getAttribute("numFmtId");
			styles.add(numberFormatId.isEmpty() ? 0 : Integer.parseInt(numberFormatId));
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
		NodeList relationships = parse(relsXml).getElementsByTagName("Relationship");
		for (int index = 0; index < relationships.getLength(); index++) {
			Element relationship = (Element) relationships.item(index);
			if (!relationship.getAttribute("Type").contains("worksheet")) {
				continue;
			}
			String digits = relationship.getAttribute("Id").replaceAll("\\D", "");
			int order = digits.isEmpty() ? 1 : Integer.parseInt(digits);
			String target = relationship.getAttribute("Target");
			sheets.put(order - 1, target.startsWith("/") ? target.substring(1) : target);
		}
		if (sheets.isEmpty()) {
			throw new LegacyXlsxException("Sheet 0 not found");
		}
		return "xl/" + sheets.firstEntry().getValue();
	}

	private static List<List<String>> parseSheet(byte[] xml, List<String> sharedStrings, List<Integer> styles) {
		TreeMap<Integer, List<String>> rows = new TreeMap<>();
		NodeList rowElements = parse(xml).getElementsByTagName("row");
		for (int index = 0; index < rowElements.getLength(); index++) {
			Element rowElement = (Element) rowElements.item(index);
			int rowIndex = Integer.parseInt(rowElement.getAttribute("r")) - 1;

			Map<Integer, String> cells = new HashMap<>();
			NodeList cellElements = rowElement.getElementsByTagName("c");
			for (int cellIndex = 0; cellIndex < cellElements.getLength(); cellIndex++) {
				Element cell = (Element) cellElements.item(cellIndex);
				int column = columnIndex(cell.getAttribute("r").replaceAll("\\d", ""));
				cells.put(column, cellValue(cell, sharedStrings, styles));
			}
			if (!cells.isEmpty()) {
				int lastColumn = cells.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
				List<String> row = new ArrayList<>();
				for (int column = 0; column <= lastColumn; column++) {
					// Sparse cells become nulls so the row is contiguous.
					row.add(cells.get(column));
				}
				rows.put(rowIndex, row);
			}
		}
		return new ArrayList<>(rows.values());
	}

	private static String cellValue(Element cell, List<String> sharedStrings, List<Integer> styles) {
		String type = cell.getAttribute("t");
		if ("inlineStr".equals(type)) {
			NodeList texts = cell.getElementsByTagName("t");
			return texts.getLength() == 0 ? "" : texts.item(0).getTextContent();
		}
		Node valueNode = firstChild(cell, "v");
		if (valueNode == null) {
			return null;
		}
		String value = valueNode.getTextContent();
		if ("s".equals(type)) {
			int position = Integer.parseInt(value);
			return position >= 0 && position < sharedStrings.size() ? sharedStrings.get(position) : "";
		}
		if ("b".equals(type)) {
			return "1".equals(value) ? "TRUE" : "FALSE";
		}
		if (type.isEmpty() || "n".equals(type)) {
			String styleAttribute = cell.getAttribute("s");
			int style = styleAttribute.isEmpty() ? 0 : Integer.parseInt(styleAttribute);
			int numberFormatId = style >= 0 && style < styles.size() ? styles.get(style) : 0;
			if (isDateFormat(numberFormatId) && isNumeric(value)) {
				return excelSerialToDateTime(Double.parseDouble(value));
			}
		}
		return value;
	}

	private static Node firstChild(Element parent, String name) {
		NodeList children = parent.getElementsByTagName(name);
		return children.getLength() == 0 ? null : children.item(0);
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
	 * Excel's serial date: day 1 is 1 January 1900, and the 1900 leap-year bug
	 * means serials above 59 are one day ahead of the real calendar -- the same
	 * offset legacy's {@code excel_serial_to_datetime_string()} applies.
	 */
	static String excelSerialToDateTime(double serial) {
		double days = serial >= 60 ? serial - 1 : serial;
		long wholeDays = (long) Math.floor(days);
		long seconds = Math.round((days - wholeDays) * 86_400d);
		LocalDateTime moment = LocalDateTime.of(1899, 12, 31, 0, 0)
				.plusDays(wholeDays)
				.plusSeconds(seconds);
		return moment.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
	}

	private static Element parse(byte[] xml) {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			// Spreadsheet parts are untrusted input: no external entities, ever.
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setExpandEntityReferences(false);
			DocumentBuilder builder = factory.newDocumentBuilder();
			try (InputStream stream = new ByteArrayInputStream(xml)) {
				return builder.parse(stream).getDocumentElement();
			}
		} catch (ParserConfigurationException | SAXException | IOException ex) {
			throw new LegacyXlsxException("Cannot read XLSX part: " + new String(xml, 0,
					Math.min(xml.length, 32), StandardCharsets.UTF_8), ex);
		}
	}

}
