package com.workin.legacy.spreadsheet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.workin.legacy.attendance.spreadsheet.LegacyAttendanceImportException;
import com.workin.legacy.attendance.spreadsheet.LegacyAttendanceImportReader;
import com.workin.legacy.employees.spreadsheet.LegacyEmployeeSpreadsheetReader;
import com.workin.legacy.workforce.LegacyLeaveBalanceSpreadsheetService;

/**
 * What one upload may cost once each endpoint keys its rows by the header
 * (D-289). The reader bounds the sheet; these shapes stay inside every reader
 * bound and would still have become tens of millions of map entries, because
 * every data row is keyed at the header's full width. Each is driven through
 * the loader the endpoint itself calls, on this thread, so the allocation
 * the refusal costs is measured where it happens.
 */
class LegacySpreadsheetKeyedRowsBoundsTest {

	/**
	 * Up to two million keyed cells are built before the refusal. Measured at
	 * 120-360 MB allocated, garbage included; without the budget the same
	 * files ran a 768 MB heap out of memory, and the employee and leave-balance
	 * headers alone cost 5.5 GB of alias folding before any row was keyed.
	 */
	private static final long ALLOCATION_BOUND = 512L * 1024 * 1024;

	@Test
	void attendanceImportRefusesAHeaderEndingAtTheLastColumnOverThousandsOfShortRows() throws IOException {
		// The review's 25 KB probe: 16,384 header columns, 3,000 two-cell rows.
		byte[] workbook = lastColumnHeaderOverShortRows();
		assertThat(workbook.length).isLessThan(64 * 1024);

		assertRefusedCheaply(() -> LegacyAttendanceImportReader.loadRows(workbook),
				LegacyAttendanceImportException.class, "Empty or unreadable file");
	}

	@Test
	void attendanceImportRefusesTheSameShapeAsCsv() {
		StringBuilder csv = new StringBuilder();
		for (int column = 0; column < 16_384; column++) {
			csv.append(column == 0 ? "" : ",").append('h').append(column);
		}
		csv.append('\n');
		for (int row = 0; row < 3_000; row++) {
			csv.append("1,2\n");
		}
		byte[] content = csv.toString().getBytes(StandardCharsets.UTF_8);
		assertThat(content.length).isLessThan(1024 * 1024);

		assertRefusedCheaply(() -> LegacyAttendanceImportReader.loadRows(content),
				LegacyAttendanceImportException.class, "Empty or unreadable file");
	}

	@Test
	void employeeImportRefusesSixteenThousandDistinctHeadersOverThreeThousandRows() throws IOException {
		byte[] workbook = sheet(zip -> {
			zip.write("<row r=\"1\">".getBytes(StandardCharsets.US_ASCII));
			for (int column = 0; column < 16_384; column++) {
				zip.write(("<c r=\"" + LegacyXlsxWriter.columnLetter(column) + "1\" t=\"inlineStr\"><is><t>h" + column
						+ "</t></is></c>").getBytes(StandardCharsets.US_ASCII));
			}
			zip.write("</row>".getBytes(StandardCharsets.US_ASCII));
			for (int row = 2; row <= 3_001; row++) {
				zip.write(("<row r=\"" + row + "\"><c r=\"A" + row + "\"><v>" + row + "</v></c></row>")
						.getBytes(StandardCharsets.US_ASCII));
			}
		});
		assertThat(workbook.length).isLessThan(1024 * 1024);

		assertRefusedCheaply(() -> LegacyEmployeeSpreadsheetReader.loadRows(workbook),
				LegacyEmployeeSpreadsheetReader.LegacySpreadsheetException.class,
				LegacyEmployeeSpreadsheetReader.EMPTY_OR_UNREADABLE);
	}

	@Test
	void leaveBalanceImportRefusesAHeaderEndingAtTheLastColumnOverThousandsOfShortRows() throws IOException {
		byte[] workbook = lastColumnHeaderOverShortRows();
		// The refusal comes before any row is parsed, so nothing here touches the store or the clock.
		LegacyLeaveBalanceSpreadsheetService service = new LegacyLeaveBalanceSpreadsheetService(null, null);

		assertRefusedCheaply(() -> service.analyze(workbook, 1L, 2026, "en"),
				IllegalArgumentException.class, "Empty or unreadable file");
	}

	@Test
	void attendanceXlsRefusesTwoHundredFiftySixColumnsOverTenThousandRows() throws IOException {
		byte[] workbook;
		try (HSSFWorkbook xls = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			HSSFSheet sheet = xls.createSheet("Sheet1");
			HSSFRow header = sheet.createRow(0);
			for (int column = 0; column < 256; column++) {
				header.createCell(column).setCellValue("h" + column);
			}
			for (int row = 1; row <= 10_000; row++) {
				HSSFRow data = sheet.createRow(row);
				data.createCell(0).setCellValue(row);
				data.createCell(1).setCellValue(row);
			}
			xls.write(out);
			workbook = out.toByteArray();
		}
		assertThat(workbook.length).isLessThan(1024 * 1024);

		assertRefusedCheaply(() -> LegacyAttendanceImportReader.loadRows(workbook),
				LegacyAttendanceImportException.class, "Cannot read XLS file. Invalid or corrupted file");
	}

	@Test
	void anOrdinaryWideSheetStillLoads() throws IOException {
		// Thirty columns by 5,000 rows is 150,000 keyed cells, far inside the budget.
		byte[] workbook = sheet(zip -> {
			zip.write("<row r=\"1\">".getBytes(StandardCharsets.US_ASCII));
			for (int column = 0; column < 30; column++) {
				zip.write(("<c r=\"" + LegacyXlsxWriter.columnLetter(column) + "1\" t=\"inlineStr\"><is><t>h" + column
						+ "</t></is></c>").getBytes(StandardCharsets.US_ASCII));
			}
			zip.write("</row>".getBytes(StandardCharsets.US_ASCII));
			for (int row = 2; row <= 5_001; row++) {
				zip.write(("<row r=\"" + row + "\"><c r=\"A" + row + "\"><v>1</v></c><c r=\"B" + row
						+ "\"><v>2</v></c></row>").getBytes(StandardCharsets.US_ASCII));
			}
		});

		assertThat(LegacyAttendanceImportReader.loadRows(workbook).rows()).hasSize(5_000);
	}

	private static byte[] lastColumnHeaderOverShortRows() throws IOException {
		return sheet(zip -> {
			zip.write("<row r=\"1\"><c r=\"XFD1\" t=\"inlineStr\"><is><t>last</t></is></c></row>"
					.getBytes(StandardCharsets.US_ASCII));
			for (int row = 2; row <= 3_001; row++) {
				zip.write(("<row r=\"" + row + "\"><c r=\"A" + row + "\"><v>1</v></c><c r=\"B" + row
						+ "\"><v>2</v></c></row>").getBytes(StandardCharsets.US_ASCII));
			}
		});
	}

	private static void assertRefusedCheaply(Executable load, Class<? extends Throwable> type, String message) {
		com.sun.management.ThreadMXBean threads =
				(com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		long before = threads.getCurrentThreadAllocatedBytes();
		assertThatThrownBy(load::execute).isInstanceOf(type).hasMessage(message);
		long allocated = threads.getCurrentThreadAllocatedBytes() - before;
		assertThat(allocated).as("bytes allocated before the refusal").isLessThan(ALLOCATION_BOUND);
	}

	private interface SheetRows {
		void write(ZipOutputStream zip) throws IOException;
	}

	private static byte[] sheet(SheetRows rows) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
			zip.write("<worksheet><sheetData>".getBytes(StandardCharsets.US_ASCII));
			rows.write(zip);
			zip.write("</sheetData></worksheet>".getBytes(StandardCharsets.US_ASCII));
			zip.closeEntry();
		}
		return out.toByteArray();
	}
}
