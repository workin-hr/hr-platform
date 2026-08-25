package com.workin.legacy.spreadsheet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * {@code xlsx_build_bytes()} ({@code hr-legacy/apis/helpers/xlsx_writer.php}),
 * on {@code java.util.zip} and hand-written XML.
 *
 * <p>The same six parts, the same relationships, the same sheet XML: every cell
 * an {@code inlineStr}, the alternating body styles, the header band, the
 * column widths derived from the longest line of each column, the merges and
 * the frozen header rows.
 *
 * <p>D-085 as amended: what must match is the generator's contract -- sheet
 * name, the two leading rows, the 28 header values, merges, styles, widths and
 * freeze -- not the archive bytes. ZIP timestamps and compression metadata are
 * incidental.
 */
public final class LegacyXlsxWriter {

	/** {@code xlsx_style_header_yellow()} / {@code xlsx_style_header_red()}. */
	public static final int STYLE_HEADER_YELLOW = 4;
	public static final int STYLE_HEADER_RED = 5;

	private LegacyXlsxWriter() {
	}

	/**
	 * Existing callers use the legacy writer's default left-to-right worksheet.
	 */
	public static byte[] build(
			List<String> headers, List<List<String>> dataRows, String sheetName,
			List<List<String>> prefixRows, List<String> merges, int headerStyleRows,
			Map<Integer, Map<Integer, Integer>> cellStyles) {
		return build(headers, dataRows, sheetName, prefixRows, merges, headerStyleRows, cellStyles, false);
	}

	/**
	 * @param headers the header row, written after any prefix rows
	 * @param dataRows body rows, may be empty
	 * @param sheetName the worksheet name, sanitised the way PHP sanitises it
	 * @param prefixRows rows written above the header (the salary group row)
	 * @param merges Excel merge references such as {@code S1:W1}
	 * @param headerStyleRows how many leading rows take the header band style
	 * @param cellStyles {@code [rowIndex][columnIndex] -> styleId} overrides
	 * @param rightToLeft whether the worksheet view carries PHP's rightToLeft flag
	 */
	public static byte[] build(
			List<String> headers, List<List<String>> dataRows, String sheetName,
			List<List<String>> prefixRows, List<String> merges, int headerStyleRows,
			Map<Integer, Map<Integer, Integer>> cellStyles, boolean rightToLeft) {
		List<List<String>> allRows = new ArrayList<>(prefixRows);
		allRows.add(headers);
		allRows.addAll(dataRows);

		int freezeTopRows = Math.max(1, headerStyleRows);
		String sheetXml = sheetXml(
				allRows, merges, Math.max(1, headerStyleRows), cellStyles, rightToLeft, freezeTopRows);
		String safeName = sheetName.replaceAll("[\\\\/*?:\\[\\]]+", "");
		if (safeName.isEmpty()) {
			safeName = "Sheet1";
		}

		Map<String, String> parts = new LinkedHashMap<>();
		parts.put("[Content_Types].xml", CONTENT_TYPES);
		parts.put("_rels/.rels", ROOT_RELS);
		parts.put("xl/workbook.xml", XML_DECLARATION
				+ "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
				+ "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
				+ "<sheets><sheet name=\"" + escape(safeName) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
		parts.put("xl/_rels/workbook.xml.rels", WORKBOOK_RELS);
		parts.put("xl/styles.xml", STYLES);
		parts.put("xl/worksheets/sheet1.xml", sheetXml);

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
			for (Map.Entry<String, String> part : parts.entrySet()) {
				ZipEntry entry = new ZipEntry(part.getKey());
				entry.setTime(0L);
				zip.putNextEntry(entry);
				zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		} catch (IOException ex) {
			throw new IllegalStateException("Cannot create XLSX file", ex);
		}
		return bytes.toByteArray();
	}

	/** {@code xlsx_sheet_xml()}, statement for statement. */
	private static String sheetXml(
			List<List<String>> rows, List<String> merges, int headerStyleRows,
			Map<Integer, Map<Integer, Integer>> cellStyles, boolean rightToLeft, int freezeTopRows) {
		StringBuilder sheetRows = new StringBuilder();
		for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
			List<String> row = rows.get(rowIndex);
			int excelRow = rowIndex + 1;
			boolean headerBand = rowIndex < headerStyleRows;
			int defaultStyle = headerBand ? 1 : ((rowIndex - headerStyleRows) % 2 == 0 ? 2 : 3);
			int maxLines = 1;
			StringBuilder cells = new StringBuilder();
			for (int columnIndex = 0; columnIndex < row.size(); columnIndex++) {
				String raw = row.get(columnIndex) == null ? "" : row.get(columnIndex);
				maxLines = Math.max(maxLines, countLines(raw));
				String spaceAttribute = raw.contains("\n") || raw.startsWith(" ") || raw.endsWith(" ")
						? " xml:space=\"preserve\"" : "";
				int styleId = cellStyles.getOrDefault(rowIndex, Map.of())
						.getOrDefault(columnIndex, defaultStyle);
				cells.append("<c r=\"").append(columnLetter(columnIndex)).append(excelRow)
						.append("\" t=\"inlineStr\" s=\"").append(styleId).append("\"><is><t")
						.append(spaceAttribute).append('>').append(escape(raw)).append("</t></is></c>");
			}
			int height = headerBand
					? Math.max(rowIndex == 0 && headerStyleRows > 1 ? 28 : 36, 18 * maxLines)
					: Math.max(22, 16 * maxLines);
			sheetRows.append("<row r=\"").append(excelRow).append("\" ht=\"").append(height)
					.append("\" customHeight=\"1\">").append(cells).append("</row>");
		}

		StringBuilder mergeXml = new StringBuilder();
		if (!merges.isEmpty()) {
			mergeXml.append("<mergeCells count=\"").append(merges.size()).append("\">");
			for (String reference : merges) {
				mergeXml.append("<mergeCell ref=\"").append(escape(reference)).append("\"/>");
			}
			mergeXml.append("</mergeCells>");
		}

		String sheetViews = "";
		if (rightToLeft || freezeTopRows > 0) {
			String rtl = rightToLeft ? " rightToLeft=\"1\"" : "";
			String pane = "";
			if (freezeTopRows > 0) {
				pane = "<pane ySplit=\"" + freezeTopRows + "\" topLeftCell=\"A" + (freezeTopRows + 1)
						+ "\" activePane=\"bottomLeft\" state=\"frozen\"/>";
			}
			sheetViews = "<sheetViews><sheetView workbookViewId=\"0\"" + rtl + ">"
					+ pane + "</sheetView></sheetViews>";
		}

		return XML_DECLARATION
				+ "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
				+ sheetViews + columnsXml(rows) + "<sheetData>" + sheetRows + "</sheetData>" + mergeXml
				+ "</worksheet>";
	}

	/** {@code xlsx_cols_xml()}: each column as wide as its longest single line, clamped to 10..28. */
	private static String columnsXml(List<List<String>> rows) {
		Map<Integer, Double> widths = new LinkedHashMap<>();
		for (List<String> row : rows) {
			for (int columnIndex = 0; columnIndex < row.size(); columnIndex++) {
				String value = row.get(columnIndex) == null ? "" : row.get(columnIndex);
				widths.merge(columnIndex, displayWidth(value), Math::max);
			}
		}
		if (widths.isEmpty()) {
			return "";
		}
		StringBuilder xml = new StringBuilder("<cols>");
		widths.forEach((columnIndex, width) -> xml.append("<col min=\"").append(columnIndex + 1)
				.append("\" max=\"").append(columnIndex + 1)
				.append("\" width=\"").append(round(width))
				.append("\" customWidth=\"1\"/>"));
		return xml.append("</cols>").toString();
	}

	/** {@code xlsx_text_display_width()}: longest line, times 1.25, plus 2, clamped to 10..28. */
	private static double displayWidth(String text) {
		int longest = 0;
		for (String line : text.split("\r\n|\n|\r", -1)) {
			longest = Math.max(longest, line.codePointCount(0, line.length()));
		}
		return Math.max(10.0d, Math.min(28.0d, longest * 1.25d + 2.0d));
	}

	/** PHP's {@code round($width, 2)} rendering: no trailing zeros, no exponent. */
	private static String round(double width) {
		double rounded = Math.round(width * 100.0d) / 100.0d;
		if (rounded == Math.rint(rounded)) {
			return String.valueOf((long) rounded);
		}
		return new java.math.BigDecimal(String.valueOf(rounded)).stripTrailingZeros().toPlainString();
	}

	/** {@code xlsx_column_letter()}: 0 becomes A, 26 becomes AA. */
	public static String columnLetter(int index) {
		StringBuilder letter = new StringBuilder();
		int value = index + 1;
		while (value > 0) {
			value--;
			letter.insert(0, (char) ('A' + (value % 26)));
			value /= 26;
		}
		return letter.toString();
	}

	private static int countLines(String value) {
		int lines = 1;
		for (int index = 0; index < value.length(); index++) {
			if (value.charAt(index) == '\n') {
				lines++;
			}
		}
		return lines;
	}

	/** {@code htmlspecialchars($v, ENT_XML1 | ENT_QUOTES, 'UTF-8')}. */
	private static String escape(String value) {
		return value.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;")
				.replace("'", "&#039;");
	}

	private static final String XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";

	private static final String CONTENT_TYPES = XML_DECLARATION
			+ "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
			+ "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
			+ "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
			+ "<Override PartName=\"/xl/workbook.xml\" "
			+ "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
			+ "<Override PartName=\"/xl/worksheets/sheet1.xml\" "
			+ "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
			+ "<Override PartName=\"/xl/styles.xml\" "
			+ "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
			+ "</Types>";

	private static final String ROOT_RELS = XML_DECLARATION
			+ "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
			+ "<Relationship Id=\"rId1\" "
			+ "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" "
			+ "Target=\"xl/workbook.xml\"/></Relationships>";

	private static final String WORKBOOK_RELS = XML_DECLARATION
			+ "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
			+ "<Relationship Id=\"rId1\" "
			+ "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" "
			+ "Target=\"worksheets/sheet1.xml\"/>"
			+ "<Relationship Id=\"rId2\" "
			+ "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" "
			+ "Target=\"styles.xml\"/></Relationships>";

	/**
	 * {@code xlsx_styles_xml()}: six cell formats -- default, the dark header
	 * band, two alternating body fills, and the yellow entitlement and red
	 * deduction headers the employee template uses.
	 */
	private static final String STYLES = XML_DECLARATION
			+ "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
			+ "<fonts count=\"3\">"
			+ "<font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
			+ "<font><b/><color rgb=\"FFFFFFFF\"/><sz val=\"11\"/><name val=\"Calibri\"/></font>"
			+ "<font><b/><color rgb=\"FF000000\"/><sz val=\"11\"/><name val=\"Calibri\"/></font>"
			+ "</fonts>"
			+ "<fills count=\"5\">"
			+ "<fill><patternFill patternType=\"none\"/></fill>"
			+ "<fill><patternFill patternType=\"gray125\"/></fill>"
			+ "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF1F4E78\"/>"
			+ "<bgColor indexed=\"64\"/></patternFill></fill>"
			+ "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFFF2CC\"/>"
			+ "<bgColor indexed=\"64\"/></patternFill></fill>"
			+ "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFF8CBAD\"/>"
			+ "<bgColor indexed=\"64\"/></patternFill></fill>"
			+ "</fills>"
			+ "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
			+ "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
			+ "<cellXfs count=\"6\">"
			+ "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
			+ "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" "
			+ "applyFill=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" "
			+ "wrapText=\"1\"/></xf>"
			+ "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\">"
			+ "<alignment vertical=\"center\" wrapText=\"1\"/></xf>"
			+ "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\">"
			+ "<alignment vertical=\"center\" wrapText=\"1\"/></xf>"
			+ "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"3\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" "
			+ "applyFill=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" "
			+ "wrapText=\"1\"/></xf>"
			+ "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"4\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" "
			+ "applyFill=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" "
			+ "wrapText=\"1\"/></xf>"
			+ "</cellXfs>"
			+ "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
			+ "</styleSheet>";

}
