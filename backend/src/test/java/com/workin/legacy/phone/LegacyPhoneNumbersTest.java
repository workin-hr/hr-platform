package com.workin.legacy.phone;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The pure half of the phone port: everything
 * {@code helpers/phone_validator_helper.php} and the table-independent parts of
 * {@code helpers/phone_countries_helper.php} decide without touching a
 * database.
 *
 * <p>These are the rules that decide which numbers become login identifiers, so
 * they are asserted against PHP's regexes and branches rather than against
 * "what a phone number should look like".
 */
class LegacyPhoneNumbersTest {

	@Test
	void digitsOnlyStripsEverythingElse() {
		assertThat(LegacyPhoneNumbers.digitsOnly("+20 (010) 123-4567")).isEqualTo("200101234567");
		assertThat(LegacyPhoneNumbers.digitsOnly("abc")).isEmpty();
		assertThat(LegacyPhoneNumbers.digitsOnly(null)).isEmpty();
	}

	@Test
	void excelCellToRawUndoesSpreadsheetNumberFormatting() {
		// sprintf('%.0f', ...) -- no exponent, no decimal point, no separator.
		assertThat(LegacyPhoneNumbers.excelCellToRaw(1012345678L)).isEqualTo("1012345678");
		assertThat(LegacyPhoneNumbers.excelCellToRaw(1.012345678E9)).isEqualTo("1012345678");
		assertThat(LegacyPhoneNumbers.excelCellToRaw("1.012345678E9")).isEqualTo("1012345678");
		assertThat(LegacyPhoneNumbers.excelCellToRaw("1012345678.0")).isEqualTo("1012345678");
		assertThat(LegacyPhoneNumbers.excelCellToRaw(new BigDecimal("1012345678.00"))).isEqualTo("1012345678");
		assertThat(LegacyPhoneNumbers.excelCellToRaw("  01012345678  ")).isEqualTo("01012345678");
		assertThat(LegacyPhoneNumbers.excelCellToRaw("")).isEmpty();
		assertThat(LegacyPhoneNumbers.excelCellToRaw(null)).isEmpty();
	}

	@Test
	void dialCodesNormaliseToThePlusForm() {
		assertThat(LegacyPhoneNumbers.normalizeDialCode("+20")).isEqualTo("+20");
		assertThat(LegacyPhoneNumbers.normalizeDialCode("20")).isEqualTo("+20");
		assertThat(LegacyPhoneNumbers.normalizeDialCode("020")).isEqualTo("+020");
		assertThat(LegacyPhoneNumbers.normalizeDialCode(" 966 ")).isEqualTo("+966");
		assertThat(LegacyPhoneNumbers.normalizeDialCode("")).isEmpty();
		assertThat(LegacyPhoneNumbers.normalizeDialCode(null)).isEmpty();
		// No digits at all: PHP leaves the value untouched rather than prefixing.
		assertThat(LegacyPhoneNumbers.normalizeDialCode("abc")).isEqualTo("abc");
	}

	@Test
	void prefixesDecodeFromJsonOrFromADelimitedString() {
		assertThat(LegacyPhoneNumbers.decodePrefixes("[\"010\",\"011\",\"012\",\"015\"]"))
				.containsExactly("010", "011", "012", "015");
		// json_decode() fails, so PHP splits on whitespace, commas and semicolons.
		assertThat(LegacyPhoneNumbers.decodePrefixes("010, 011;012 015"))
				.containsExactly("010", "011", "012", "015");
		assertThat(LegacyPhoneNumbers.decodePrefixes(List.of("050", "052"))).containsExactly("050", "052");
	}

	@Test
	void prefixDecodingStripsNonDigitsBlanksAndDuplicates() {
		assertThat(LegacyPhoneNumbers.decodePrefixes("[\"0-1-0\",\"010\",\"\",\"abc\",\"011\"]"))
				.containsExactly("010", "011");
		assertThat(LegacyPhoneNumbers.decodePrefixes("[]")).isEmpty();
		assertThat(LegacyPhoneNumbers.decodePrefixes("")).isEmpty();
		assertThat(LegacyPhoneNumbers.decodePrefixes(null)).isEmpty();
	}

	@Test
	void jsonIsDecodedAsJsonNotSplitOnCommas() {
		// The case a comma-split gets wrong: PHP yields ["010"], a split yields
		// ["01","0"]. Verified against PHP 8.3.
		assertThat(LegacyPhoneNumbers.decodePrefixes("[\"01,0\"]")).containsExactly("010");
		// Escape sequences are decoded before the digit strip. The backslash is
		// built rather than written: javac translates a literal \-u-XXXX in
		// *source* before parsing, so a hand-written one would arrive here
		// already decoded and prove nothing about the JSON decoder.
		String backslash = String.valueOf((char) 92);
		String escaped = "[\"" + backslash + "u0030" + backslash + "u0031" + backslash + "u0030\"]";
		assertThat(escaped).doesNotContain("010");
		assertThat(LegacyPhoneNumbers.decodePrefixes(escaped)).containsExactly("010");
		// Whitespace inside a JSON string survives decoding and is stripped after.
		assertThat(LegacyPhoneNumbers.decodePrefixes("[\" 010 \"]")).containsExactly("010");
	}

	@Test
	void jsonElementsTakePhpsStringCast() {
		// (string) casts: numbers stringify, true is "1", false and null are "".
		assertThat(LegacyPhoneNumbers.decodePrefixes("[10,\"011\"]")).containsExactly("10", "011");
		assertThat(LegacyPhoneNumbers.decodePrefixes("[true,false,null]")).containsExactly("1");
		assertThat(LegacyPhoneNumbers.decodePrefixes("[1.5]")).containsExactly("15");
		// A nested array or object casts to "Array", which contributes no digits.
		assertThat(LegacyPhoneNumbers.decodePrefixes("[{\"prefix\":\"010\"},[\"011\"]]")).isEmpty();
	}

	@Test
	void aJsonObjectRootIteratesItsValuesRatherThanFallingBackToTheSplit() {
		// json_decode($raw, true) turns an object into an associative array, so
		// is_array() is true and PHP never reaches the delimiter split. Proven
		// by the case where the two disagree: {"a":"01,0"} is ["010"] in PHP,
		// but ["01","0"] under a split.
		assertThat(LegacyPhoneNumbers.decodePrefixes("{\"a\":\"010\"}")).containsExactly("010");
		assertThat(LegacyPhoneNumbers.decodePrefixes("{\"a\":\"01,0\"}")).containsExactly("010");
		assertThat(LegacyPhoneNumbers.decodePrefixes("{\"x\":\"010\",\"y\":\"011\"}"))
				.containsExactly("010", "011");
	}

	@Test
	void aScalarOrUndecodableRootFallsBackToTheDelimiterSplit() {
		// is_array() is false for a decoded scalar, so the raw text is split.
		assertThat(LegacyPhoneNumbers.decodePrefixes("\"010\"")).containsExactly("010");
		assertThat(LegacyPhoneNumbers.decodePrefixes("123")).containsExactly("123");
		assertThat(LegacyPhoneNumbers.decodePrefixes("not json at all")).isEmpty();
		assertThat(LegacyPhoneNumbers.decodePrefixes("[\"010\",")).containsExactly("010");
	}

	@Test
	void lookupVariantsCoverEveryEgyptianSpelling() {
		assertThat(LegacyPhoneNumbers.lookupVariants("01012345678"))
				.containsExactly("01012345678", "1012345678", "201012345678");
		assertThat(LegacyPhoneNumbers.lookupVariants("1012345678"))
				.containsExactly("1012345678", "01012345678", "201012345678");
		assertThat(LegacyPhoneNumbers.lookupVariants("201012345678"))
				.containsExactly("201012345678", "01012345678", "1012345678");
		// Formatting is stripped before matching.
		assertThat(LegacyPhoneNumbers.lookupVariants("+20 (10) 1234-5678"))
				.contains("201012345678", "01012345678", "1012345678");
		// A non-Egyptian number has exactly one variant: itself.
		assertThat(LegacyPhoneNumbers.lookupVariants("0512345678")).containsExactly("0512345678");
		assertThat(LegacyPhoneNumbers.lookupVariants("   ")).isEmpty();
	}

	@Test
	void theSqlExpressionStripsTheFormattingLegacyAllowsInTheColumn() {
		String expression = LegacyPhoneNumbers.digitsSqlExpression("phone");
		assertThat(expression).startsWith("REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(TRIM(COALESCE(phone, ''))");
		assertThat(expression).contains("'+', ''").contains("'-', ''").contains("' ', ''");
	}

	@Test
	void theLegacyFallbackValidatorKeepsItsThreeCountriesAndRejectsTheRest() {
		// phone_is_valid_local_legacy(): the pre-table rules, still reached when
		// a dial code has no active phone_countries row.
		assertThat(LegacyPhoneNumbers.isValidLocalLegacy("+20", "01012345678")).isTrue();
		assertThat(LegacyPhoneNumbers.isValidLocalLegacy("+20", "1012345678")).isTrue();
		assertThat(LegacyPhoneNumbers.isValidLocalLegacy("+20", "01312345678")).isFalse();
		assertThat(LegacyPhoneNumbers.isValidLocalLegacy("+966", "0512345678")).isTrue();
		assertThat(LegacyPhoneNumbers.isValidLocalLegacy("+966", "512345678")).isTrue();
		assertThat(LegacyPhoneNumbers.isValidLocalLegacy("+971", "0501234567")).isTrue();
		assertThat(LegacyPhoneNumbers.isValidLocalLegacy("+971", "0511234567")).isFalse();
		assertThat(LegacyPhoneNumbers.isValidLocalLegacy("+218", "0912345678")).isFalse();
		assertThat(LegacyPhoneNumbers.isValidLocalLegacy("", "01012345678")).isFalse();
	}

}
