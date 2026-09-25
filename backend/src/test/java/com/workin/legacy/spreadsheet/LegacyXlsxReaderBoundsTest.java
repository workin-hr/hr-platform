package com.workin.legacy.spreadsheet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

/**
 * What one uploaded workbook may cost to open (D-289). Every archive here is
 * built in the test, so what it inflates to is known exactly.
 */
class LegacyXlsxReaderBoundsTest {

	private static final String SHEET_PATH = "xl/worksheets/sheet1.xml";

	private static final String SHEET = "<worksheet><sheetData><row r=\"1\">"
			+ "<c r=\"A1\" t=\"inlineStr\"><is><t>Emp Code</t></is></c>"
			+ "<c r=\"B1\" t=\"inlineStr\"><is><t>DateTime</t></is></c>"
			+ "</row></sheetData></worksheet>";

	@Test
	void anOrdinaryWorkbookStillReads() throws IOException {
		assertThat(LegacyXlsxReader.readFirstSheet(zip(SHEET, 0, 0)))
				.containsExactly(List.of("Emp Code", "DateTime"));
	}

	@Test
	void aPartThatInflatesPastItsLimitIsRefusedAsUnreadable() throws IOException {
		// About 33 MB of blanks inside the sheet deflates to a few dozen KB.
		byte[] bomb = zip(SHEET, 1, LegacyXlsxReader.MAX_PART_BYTES + 1024);
		assertThat(bomb.length).isLessThan(1024 * 1024);

		assertThatThrownBy(() -> LegacyXlsxReader.readFirstSheet(bomb))
				.isInstanceOf(LegacyXlsxReader.LegacyXlsxException.class)
				.hasMessageContaining("size limit");
	}

	@Test
	void partsThatInflatePastTheTotalTogetherAreRefused() throws IOException {
		// Three parts each under the per-part limit, together over the total.
		byte[] bomb = zip(SHEET, 3, 30 * 1024 * 1024);

		assertThatThrownBy(() -> LegacyXlsxReader.readFirstSheet(bomb))
				.isInstanceOf(LegacyXlsxReader.LegacyXlsxException.class)
				.hasMessageContaining("size limit");
	}

	@Test
	void tooManyEntriesAreRefusedEvenBesideAValidSheet() throws IOException {
		byte[] crowded = zip(SHEET, LegacyXlsxReader.MAX_ENTRIES, 0);

		assertThatThrownBy(() -> LegacyXlsxReader.readFirstSheet(crowded))
				.isInstanceOf(LegacyXlsxReader.LegacyXlsxException.class)
				.hasMessageContaining("too many parts");
	}

	@Test
	void aCellReferencePastExcelsLastColumnIsRefused() throws IOException {
		// ZZZZZ is column 12,356,629: the row would be filled out to it with nulls.
		String sheet = "<worksheet><sheetData><row r=\"1\">"
				+ "<c r=\"ZZZZZ1\" t=\"inlineStr\"><is><t>x</t></is></c>"
				+ "</row></sheetData></worksheet>";

		assertThatThrownBy(() -> LegacyXlsxReader.readFirstSheet(zip(sheet, 0, 0)))
				.isInstanceOf(LegacyXlsxReader.LegacyXlsxException.class)
				.hasMessageContaining("out of range");
	}

	/**
	 * The sheet, plus {@code padding} further parts of {@code paddingBytes}
	 * blanks each -- or, when {@code paddingBytes} is past the per-part limit,
	 * the blanks go inside the sheet itself.
	 */
	private static byte[] zip(String sheet, int padding, int paddingBytes) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry(SHEET_PATH));
			if (padding == 1 && paddingBytes > LegacyXlsxReader.MAX_PART_BYTES) {
				String open = "<worksheet><sheetData>";
				zip.write(open.getBytes(StandardCharsets.UTF_8));
				blanks(zip, paddingBytes);
				zip.write("</sheetData></worksheet>".getBytes(StandardCharsets.UTF_8));
				padding = 0;
			} else {
				zip.write(sheet.getBytes(StandardCharsets.UTF_8));
			}
			zip.closeEntry();
			for (int part = 0; part < padding; part++) {
				zip.putNextEntry(new ZipEntry("xl/padding" + part + ".xml"));
				blanks(zip, paddingBytes);
				zip.closeEntry();
			}
		}
		return out.toByteArray();
	}

	private static void blanks(ZipOutputStream zip, int count) throws IOException {
		byte[] chunk = new byte[64 * 1024];
		java.util.Arrays.fill(chunk, (byte) ' ');
		for (int written = 0; written < count; written += chunk.length) {
			zip.write(chunk, 0, Math.min(chunk.length, count - written));
		}
	}
}
