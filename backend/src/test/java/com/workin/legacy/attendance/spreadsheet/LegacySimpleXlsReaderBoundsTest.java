package com.workin.legacy.attendance.spreadsheet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.List;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * The grid {@code SimpleXLS} sizes from the file's own {@code DIMENSION}
 * record is bounded by the rows that exist (D-289).
 */
class LegacySimpleXlsReaderBoundsTest {

	@Test
	void aForgedDimensionSizesNothingPastTheRowsThatExist() {
		// Unbounded, the reader allocated a 2^31-row list before reading a cell.
		List<List<String>> grid = LegacySimpleXlsReader.readFirstSheet(LegacyXlsFixtures.forgedDimension());

		assertThat(grid).hasSize(4);
		assertThat(grid).allSatisfy(row -> assertThat(row).hasSize(LegacySimpleXlsReader.MAX_COLUMNS));
		assertThat(grid.get(0).subList(0, 2)).containsExactly("Emp Code", "DateTime");
		assertThat(grid.get(3).subList(0, 3)).containsExactly("555005", "26/04/2026 09:00", "");
	}

	@Test
	void anHonestDimensionReadsExactlyAsBefore() {
		List<List<String>> grid = LegacySimpleXlsReader.readFirstSheet(LegacyXlsFixtures.punchOrdinary());

		assertThat(grid).hasSize(4);
		assertThat(grid.get(1)).containsExactly("555004", "26/04/2026 08:03");
	}

	@Test
	void oneCellOnTheLastRowDoesNotBuildTheWholeGrid() throws IOException {
		// A few KB of file whose DIMENSION -- here honest, since the one cell
		// really is at row 65535, column 255 -- is BIFF8's whole sheet. Built
		// densely, that was 16.7 million slots and some 140 MB per upload.
		byte[] corner;
		try (HSSFWorkbook workbook = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			workbook.createSheet("Sheet1").createRow(LegacySimpleXlsReader.MAX_ROWS - 1)
					.createCell(LegacySimpleXlsReader.MAX_COLUMNS - 1).setCellValue("corner");
			workbook.write(out);
			corner = out.toByteArray();
		}
		assertThat(corner.length).isLessThan(16 * 1024);

		com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		long before = threads.getCurrentThreadAllocatedBytes();
		List<List<String>> grid = LegacySimpleXlsReader.readFirstSheet(corner);
		long allocated = threads.getCurrentThreadAllocatedBytes() - before;

		// The same grid as before, position for position.
		assertThat(grid).hasSize(LegacySimpleXlsReader.MAX_ROWS);
		List<String> blank = Collections.nCopies(LegacySimpleXlsReader.MAX_COLUMNS, "");
		assertThat(grid.get(0)).isEqualTo(blank);
		assertThat(grid.get(LegacySimpleXlsReader.MAX_ROWS - 2)).isEqualTo(blank);
		List<String> last = grid.get(LegacySimpleXlsReader.MAX_ROWS - 1);
		assertThat(last).hasSize(LegacySimpleXlsReader.MAX_COLUMNS);
		assertThat(last.get(LegacySimpleXlsReader.MAX_COLUMNS - 1)).isEqualTo("corner");
		assertThat(last.subList(0, LegacySimpleXlsReader.MAX_COLUMNS - 1)).containsOnly("");
		assertThatThrownBy(() -> grid.get(0).get(LegacySimpleXlsReader.MAX_COLUMNS))
				.isInstanceOf(IndexOutOfBoundsException.class);
		assertThatThrownBy(() -> grid.get(0).set(0, "x")).isInstanceOf(UnsupportedOperationException.class);

		assertThat(allocated).as("bytes allocated to read a one-cell sheet").isLessThan(32L * 1024 * 1024);
	}
}
