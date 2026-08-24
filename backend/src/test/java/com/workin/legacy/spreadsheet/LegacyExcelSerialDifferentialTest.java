package com.workin.legacy.spreadsheet;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link LegacyXlsxReader#excelSerialToDateTime} against PHP's
 * {@code excel_serial_to_datetime_string()} (`xlsx_parser.php:334-345`).
 *
 * <p>Wave 12.6's attendance punch parser needs Excel-serial conversion, and
 * PHP implements it in the same helper file the employee spreadsheet stack
 * uses. Rather than assume the two are the same function, this pins the
 * differential: if any row ever disagrees, the reuse decision is wrong and a
 * second converter is required.
 *
 * <p>Every expectation is the measured output of PHP 8.3 under
 * {@code Etc/GMT-2} -- the timezone is irrelevant by design, because the PHP
 * helper uses {@code gmdate()} precisely so a server offset cannot shift a
 * punch time, and the Java side formats in UTC for the same reason.
 *
 * <p>Note 59, 60 and 61: Excel's phantom 1900-02-29 is <b>not</b> corrected by
 * either implementation, so 60 is 1900-02-28 and 61 is 1900-03-01. That is the
 * D-085 correction, and these rows keep it honest.
 */
class LegacyExcelSerialDifferentialTest {

	@ParameterizedTest(name = "serial {0} -> {1}")
	@CsvSource({
		"1,          '1899-12-31'",
		"59,         '1900-02-27'",
		"60,         '1900-02-28'",
		"61,         '1900-03-01'",
		"25569,      '1970-01-01'",
		"25569.5,    '1970-01-01 12:00:00'",
		"45000.25,   '2023-03-15 06:00:00'",
	})
	void matchesPhpExcelSerialToDatetimeString(double serial, String expected) {
		assertThat(LegacyXlsxReader.excelSerialToDateTime(serial)).isEqualTo(expected);
	}

}
