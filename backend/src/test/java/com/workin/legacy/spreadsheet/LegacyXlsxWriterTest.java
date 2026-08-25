package com.workin.legacy.spreadsheet;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

class LegacyXlsxWriterTest {

	@Test
	void penaltyReportKeepsNineHeadersButSevenBodyCellsAndFrozenHeader() throws Exception {
		byte[] bytes = LegacyXlsxWriter.build(
				List.of("Code", "Name", "Branch", "Type", "Penalty Days", "Reason", "Date", "Applied to Payroll", "Added By"),
				List.of(List.of("Karim", "Cairo", "absence", "1", "late", "2026-08-25", "0")),
				"Report", List.of(), List.of(), 1, Map.of());

		String sheet = entry(bytes, "xl/worksheets/sheet1.xml");
		assertThat(sheet).contains("ySplit=\"1\"").contains("topLeftCell=\"A2\"");
		assertThat(sheet).contains("r=\"I1\"");
		assertThat(sheet).contains("r=\"G2\"");
		assertThat(sheet).doesNotContain("r=\"H2\"").doesNotContain("r=\"I2\"");
	}

	@Test
	void workbookSanitizesSheetNameLikePhpWriter() throws Exception {
		byte[] bytes = LegacyXlsxWriter.build(List.of("A"), List.of(), "R/e:p*o?r[t]", List.of(), List.of(), 1, Map.of());
		assertThat(entry(bytes, "xl/workbook.xml")).contains("name=\"Report\"");
	}

	private static String entry(byte[] bytes, String wanted) throws Exception {
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
			for (java.util.zip.ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
				if (wanted.equals(entry.getName())) return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
			}
		}
		throw new AssertionError("missing XLSX entry " + wanted);
	}
}
