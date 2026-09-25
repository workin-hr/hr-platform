package com.workin.legacy.attendance.spreadsheet;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

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
}
