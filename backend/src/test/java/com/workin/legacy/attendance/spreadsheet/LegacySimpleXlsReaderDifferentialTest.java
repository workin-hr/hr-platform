package com.workin.legacy.attendance.spreadsheet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link LegacySimpleXlsReader} against the real vendored
 * {@code Shuchkin\SimpleXLS} (D-097).
 *
 * <h2>Where the expectations come from</h2>
 * <p>Every grid below is the measured output of {@code SimpleXLS::rows()} under
 * PHP 8.3, taken by running {@code hr-legacy/apis/helpers/SimpleXLS.php} over
 * the committed fixture bytes in {@code legacy/spreadsheet/xls} -- the same
 * bytes this test reads. Nothing here was derived from POI's own reading of a
 * cell, which is the point: POI supplies structure and legacy supplies meaning,
 * and only a differential can prove the seam.
 *
 * <p>The fixtures were authored with POI ({@link LegacyXlsFixtures}), which is
 * allowed precisely because the oracle is still PHP's answer to those bytes.
 *
 * <h2>Two levels are pinned, because two levels are observable</h2>
 * <p>The grid is what {@code rows()} returns. The associative rows are what
 * {@code parse_legacy_xls_spreadsheet()} makes of it, and they are not the same
 * thing: the wrapper shifts a header off, normalises it, and discards any row
 * with fewer than two non-blank cells. A reader can be right about the grid and
 * still hand the importer the wrong rows.
 */
class LegacySimpleXlsReaderDifferentialTest {

	// ---------------------------------------------------------------- grids --

	@Test
	void anOrdinaryTwoColumnPunchLogMatchesPhp() {
		assertThat(grid("punch_ordinary.xls")).isEqualTo(List.of(
				List.of("Emp Code", "DateTime"),
				List.of("555004", "26/04/2026 08:03"),
				List.of("555004", "26/04/2026 17:10"),
				List.of("555005", "26/04/2026 09:00")));
	}

	@Test
	void aReversedPunchLogMatchesPhp() {
		assertThat(grid("punch_reversed.xls")).isEqualTo(List.of(
				List.of("Time", "No."),
				List.of("26/04/2026 08:03", "555004"),
				List.of("26/04/2026 17:10", "555004"),
				List.of("26/04/2026 09:00", "555005")));
	}

	/**
	 * Shared strings, including the run that spills a BIFF8 {@code SST} into
	 * {@code CONTINUE} records.
	 *
	 * <p>Forty 300-character strings clear the 8224-byte record ceiling, so the
	 * continuation bookkeeping is genuinely exercised rather than assumed. PHP
	 * returned all 42 rows with the values spelled out below; the loop states
	 * the same expectation rather than repeating 12KB of literals.
	 */
	@Test
	void sharedStringsSurviveTheContinueBoundary() {
		List<List<String>> grid = grid("sst_strings.xls");
		assertThat(grid).hasSize(42);
		assertThat(grid.get(0)).isEqualTo(List.of("Label", "Value"));
		for (int index = 1; index <= 40; index++) {
			assertThat(grid.get(index))
					.describedAs("row %d", index)
					.isEqualTo(List.of("repeated label", ("row " + index + " ").repeat(30)));
		}
		assertThat(grid.get(41)).isEqualTo(List.of("repeated label", "repeated label"));
	}

	/**
	 * Holes come back as empty strings, not as short rows.
	 *
	 * <p>{@code readRows()} walks the {@code DIMENSION} rectangle and fills
	 * every position it finds no cell for, so a missing cell and an explicitly
	 * blank one are indistinguishable, and a row that was never written at all
	 * still appears -- row 4 here is three empty strings.
	 */
	@Test
	void missingAndBlankCellsAreBothEmptyStrings() {
		assertThat(grid("sparse.xls")).isEqualTo(List.of(
				List.of("A", "B", "C"),
				List.of("a1", "", "c1"),
				List.of("a2", "", "c2"),
				List.of("a3", "", ""),
				List.of("", "", ""),
				List.of("a5", "b5", "c5")));
	}

	@Test
	void arabicTextRoundTrips() {
		assertThat(grid("unicode.xls")).isEqualTo(List.of(
				List.of("كود الموظف", "التاريخ"),
				List.of("555004", "26/04/2026 08:03"),
				List.of("٥٥٥٠٠٤", "مساءً")));
	}

	/**
	 * The numeric rendering table, which is where POI and legacy part company.
	 *
	 * <p>Three rows carry the weight. {@code two decimals} is the reviewer's
	 * case: an integral value in a {@code 0.00} cell is {@code "555004.00"},
	 * and rendering it as {@code 555004} because POI knows it is integral would
	 * pass the punch-log digits guard that legacy fails. {@code half up} is
	 * {@code "2"} for 2.5 -- PHP's {@code sprintf} rounds half to <b>even</b>,
	 * which is the opposite of what {@code String.format} does. And
	 * {@code general repeating} is 14 significant digits, PHP's default float
	 * precision, not Java's 17.
	 */
	@Test
	void numericCellsFollowSimpleXlsSprintf() {
		assertThat(grid("numbers.xls")).isEqualTo(List.of(
				List.of("Case", "Value"),
				List.of("general integer", "555004"),
				List.of("general fraction", "1.5"),
				List.of("general repeating", "0.33333333333333"),
				List.of("general negative", "-12.25"),
				List.of("two decimals", "555004.00"),
				List.of("zero decimals", "1235"),
				List.of("thousands", "1234.56"),
				List.of("currency", "99.5"),
				List.of("percent", "8%"),
				List.of("percent decimals", "7.50%"),
				List.of("custom non date", "1234.5678"),
				List.of("text format", "555004"),
				List.of("half up", "2"),
				List.of("big", "1.0E+20")));
	}

	/**
	 * Date cells, and the two rules that make them not the XLSX helper.
	 *
	 * <p>{@code date only} keeps {@code 00:00:00} -- {@code $datetimeFormat} is
	 * unconditional, where {@code excel_serial_to_datetime_string()} drops the
	 * time half when it is zero. And {@code sub one serial} is noon on 1
	 * January <b>1970</b>, because {@code createDate()} only subtracts the
	 * epoch when the serial exceeds 1, so a time-only cell is not a time of day
	 * at all. {@code excel phantom leap} confirms serial 60 stays 28 February
	 * 1900, uncorrected on both sides.
	 */
	@Test
	void dateCellsAlwaysCarryATimeHalf() {
		assertThat(grid("dates.xls")).isEqualTo(List.of(
				List.of("Case", "Value"),
				List.of("date only", "2026-04-26 00:00:00"),
				List.of("date and time", "2026-04-26 08:03:00"),
				List.of("time only format", "2026-04-26 08:03:00"),
				List.of("sub one serial", "1970-01-01 12:00:00"),
				List.of("serial one", "1970-01-02 00:00:00"),
				List.of("serial two", "1900-01-01 00:00:00"),
				List.of("excel phantom leap", "1900-02-28 00:00:00"),
				List.of("custom escaped", "2026-04-26 12:00:00"),
				List.of("custom slashes", "2026-04-26 12:00:00")));
	}

	/**
	 * The same serials in a 1904-window workbook.
	 *
	 * <p>Only the rows above the {@code > 1} threshold move, and they move by
	 * the 1462-day epoch difference: 26 April 2026 becomes 27 April 2030. The
	 * two sub-one rows are identical to the 1900 file, which is the tell that
	 * the epoch is skipped rather than applied differently.
	 */
	@Test
	void theNineteenFourWindowShiftsOnlyRealSerials() {
		assertThat(grid("dates_1904.xls")).isEqualTo(List.of(
				List.of("Case", "Value"),
				List.of("date only", "2030-04-27 00:00:00"),
				List.of("date and time", "2030-04-27 08:03:00"),
				List.of("time only format", "2030-04-27 08:03:00"),
				List.of("sub one serial", "1970-01-01 12:00:00"),
				List.of("serial one", "1970-01-02 00:00:00"),
				List.of("serial two", "1904-01-03 00:00:00"),
				List.of("excel phantom leap", "1904-03-01 00:00:00"),
				List.of("custom escaped", "2030-04-27 12:00:00"),
				List.of("custom slashes", "2030-04-27 12:00:00")));
	}

	/**
	 * Cached formula results, and the four that vanish.
	 *
	 * <p>{@code parseSheet()} adds a cell for a formula only when the cached
	 * result is numeric. A cached string, a concatenation, a boolean and an
	 * error all fail its guard and are never recorded, so they read as empty --
	 * measured, and worth pinning because a reader that asked POI for the
	 * cached string would happily produce {@code "HELLO"} instead.
	 */
	@Test
	void onlyNumericCachedFormulaResultsSurvive() {
		assertThat(grid("formulas.xls")).isEqualTo(List.of(
				List.of("Case", "Value"),
				List.of("cached numeric", "6"),
				List.of("cached numeric date", "2026-04-26 00:00:00"),
				List.of("cached string", ""),
				List.of("cached concat", ""),
				List.of("cached boolean", ""),
				List.of("cached error", "")));
	}

	/**
	 * {@code BOOLERR} is the raw byte: 1, 0, and 7 for {@code #DIV/0!}.
	 *
	 * <p>Legacy leaves those as PHP integers rather than strings. That is
	 * invisible to everything downstream -- the punch parser's first branch is
	 * {@code is_int($raw) || ... || is_numeric(trim($raw))}, which takes an int
	 * and its decimal string down the same path, and every other consumer casts
	 * -- so they are strings here.
	 */
	@Test
	void booleanAndErrorCellsAreTheirRawByte() {
		assertThat(grid("bool_error.xls")).isEqualTo(List.of(
				List.of("Case", "Value"),
				List.of("true", "1"),
				List.of("false", "0"),
				List.of("div by zero", "7")));
	}

	@Test
	void onlyTheFirstSheetIsEverRead() {
		assertThat(grid("multi_sheet.xls")).isEqualTo(List.of(
				List.of("Emp Code", "DateTime"),
				List.of("555004", "26/04/2026 08:03")));
	}

	/**
	 * A sheet with no cells is one empty cell, not nothing.
	 *
	 * <p>{@code DIMENSION} still claims a 1x1 rectangle, and {@code readRows()}
	 * believes it. The wrapper then shifts that single row off as the header
	 * and returns nothing, so the oddity is invisible past this point -- but it
	 * is what {@code rows()} says, and this reproduces {@code rows()}.
	 */
	@Test
	void anEmptySheetIsASingleEmptyCell() {
		assertThat(grid("empty_sheet.xls")).isEqualTo(List.of(List.of("")));
	}

	// ----------------------------------------------------------- rejections --

	/**
	 * An encrypted workbook reads as no rows at all, exactly as legacy's does.
	 *
	 * <p>{@code parseEntries()} returns {@code false} the moment it sees
	 * {@code FILEPASS}, but it sets no error -- so {@code parseFile()} still
	 * reports success, {@code rows()} finds no sheet, and the caller gets an
	 * empty array. Measured. The endpoint consequence is a 400
	 * {@code attendance_excel_must_have_two_columns}, <b>not</b>
	 * {@code invalid_file_type}, because the failure surfaces as an empty sheet
	 * rather than an unreadable file.
	 *
	 * <p>POI could decrypt some of these -- it tries Excel's well-known default
	 * password unprompted -- so the adapter installs a password that cannot
	 * match for the duration of the parse. The refusal is deliberate.
	 */
	@Test
	void anEncryptedWorkbookIsEmptyRatherThanUnreadable() {
		assertThat(grid("encrypted.xls")).isEmpty();
	}

	/**
	 * D-097: pre-97 BIFF is refused, through either marker.
	 *
	 * <p>{@code biff5.xls} carries the older version in a {@code Workbook}
	 * stream, which is what the adapter's own gate catches;
	 * {@code biff5_book_stream.xls} carries the Excel 5/95 stream name, which
	 * is what POI catches. Both end at the same wire response.
	 *
	 * <p>Legacy reads <b>both</b> of them -- PHP returned the three punch rows
	 * for each, via {@code SimpleXLS}' incidental {@code BIFF7 = 0x500} branch.
	 * So this is the accepted divergence D-097 names, not an unnoticed gap, and
	 * it is pinned here so it stays deliberate.
	 */
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"biff5.xls", "biff5_book_stream.xls"})
	void preNinetySevenWorkbooksAreRefused(String fixture) {
		assertThatThrownBy(() -> grid(fixture))
				.isInstanceOf(LegacyAttendanceImportException.class)
				.hasMessage("Cannot read XLS file. Invalid or corrupted file");
	}

	/**
	 * A corrupt container is refused in bounded time, which legacy is not.
	 *
	 * <p>Neither of these has a legacy behaviour worth reproducing.
	 * {@code truncated.xls} sends {@code parseSheet()} walking off the end of
	 * the workbook stream, incrementing four bytes at a time and warning on
	 * every read: it had not finished after two minutes and does not terminate
	 * on its own. {@code not_a_workbook.xls} is a valid OLE2 container with no
	 * {@code Workbook} stream, and legacy exhausts the 128MB memory limit and
	 * dies with a PHP fatal. Both were measured.
	 *
	 * <p>So the response here is the nearest envelope legacy <em>can</em>
	 * produce -- the {@code parse_legacy_xls_spreadsheet()} throw -- rather
	 * than a reproduction of a hang. Flagged as a bounded divergence; it is not
	 * covered by D-097's text.
	 */
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"truncated.xls", "not_a_workbook.xls"})
	void anUnreadableContainerIsRefusedDeterministically(String fixture) {
		assertThatThrownBy(() -> grid(fixture))
				.isInstanceOf(LegacyAttendanceImportException.class)
				.hasMessage("Cannot read XLS file. Invalid or corrupted file");
	}

	// -------------------------------------------------------------- wrapper --

	/**
	 * {@code parse_legacy_xls_spreadsheet()} on top of the grid, and then
	 * {@code attendance_import_load_rows()}' XLS branch on top of that.
	 *
	 * <p>Each expectation is the format, the normalised keys and the surviving
	 * row count PHP reported for the same file. Three of them are the reason
	 * this level is pinned separately: {@code sparse} keeps three of its five
	 * data rows, because the one-cell row and the empty row both fail the
	 * "two non-blank cells" filter; {@code formulas} keeps two of six, because
	 * the four vanished formula results leave those rows with a single filled
	 * cell; and {@code sparse} reports {@code punch_log} despite having three
	 * columns, because the XLS branch's {@code unknown -> punch_log} fallback
	 * is unconditional where the CSV/XLSX path's is not.
	 */
	@ParameterizedTest(name = "{0} -> {1}, {2} rows")
	@org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
		"punch_ordinary.xls    | punch_log | emp_code,datetime | 3",
		"punch_reversed.xls    | punch_log | time,no.          | 3",
		"sst_strings.xls       | punch_log | label,value       | 41",
		"sparse.xls            | punch_log | a,b,c             | 3",
		"numbers.xls           | punch_log | case,value        | 14",
		"dates.xls             | punch_log | case,value        | 9",
		"dates_1904.xls        | punch_log | case,value        | 9",
		"formulas.xls          | punch_log | case,value        | 2",
		"bool_error.xls        | punch_log | case,value        | 3",
		"multi_sheet.xls       | punch_log | emp_code,datetime | 1",
		"empty_sheet.xls       | punch_log |                   | 0",
		"encrypted.xls         | punch_log |                   | 0",
	})
	void theWrapperAndLoadRowsMatchPhp(String fixture, String format, String keys, int rows) {
		LegacyAttendanceImportReader.Loaded loaded = LegacyAttendanceImportReader.loadRows(bytes(fixture));
		assertThat(loaded.format()).isEqualTo(format);
		assertThat(loaded.keys()).isEqualTo(keys == null ? List.of() : List.of(keys.split(",")));
		assertThat(loaded.rows()).hasSize(rows);
	}

	/** The Arabic keys, kept out of the CSV table because a comma-split cannot carry them safely. */
	@Test
	void arabicHeadersNormaliseAsPhpNormalisesThem() {
		LegacyAttendanceImportReader.Loaded loaded =
				LegacyAttendanceImportReader.loadRows(bytes("unicode.xls"));
		assertThat(loaded.format()).isEqualTo("punch_log");
		assertThat(loaded.keys()).isEqualTo(List.of("كود_الموظف", "التاريخ"));
		assertThat(loaded.rows()).hasSize(2);
		assertThat(loaded.rows().get(1))
				.isEqualTo(Map.of("كود_الموظف", "٥٥٥٠٠٤", "التاريخ", "مساءً"));
	}

	/**
	 * The whole point of the wrapper level: what the importer actually reads.
	 *
	 * <p>An ordinary punch log arrives as three associative rows keyed by the
	 * normalised header, values untouched.
	 */
	@Test
	void anOrdinaryPunchLogReachesTheImporterAsPhpRows() {
		LegacyAttendanceImportReader.Loaded loaded =
				LegacyAttendanceImportReader.loadRows(bytes("punch_ordinary.xls"));
		List<Map<String, Object>> expected = new ArrayList<>();
		expected.add(Map.of("emp_code", "555004", "datetime", "26/04/2026 08:03"));
		expected.add(Map.of("emp_code", "555004", "datetime", "26/04/2026 17:10"));
		expected.add(Map.of("emp_code", "555005", "datetime", "26/04/2026 09:00"));
		assertThat(loaded.rows()).isEqualTo(expected);
	}

	// --------------------------------------------------------------- helpers --

	private static List<List<String>> grid(String fixture) {
		return LegacySimpleXlsReader.readFirstSheet(bytes(fixture));
	}

	private static byte[] bytes(String fixture) {
		String resource = "/legacy/spreadsheet/xls/" + fixture;
		try (InputStream stream = LegacySimpleXlsReaderDifferentialTest.class.getResourceAsStream(resource)) {
			if (stream == null) {
				throw new IllegalStateException("missing fixture " + resource);
			}
			return stream.readAllBytes();
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
