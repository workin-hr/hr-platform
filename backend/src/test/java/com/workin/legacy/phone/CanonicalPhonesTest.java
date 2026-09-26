package com.workin.legacy.phone;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * The one normalizer (ADR-0020, D-291): which inputs are phone numbers, which
 * number each one is, and what an account may be given. No database -- the
 * countries these cases need are the ones offered without a table.
 */
class CanonicalPhonesTest {

	private static final DataSource UNUSED = new AbstractDataSource() {
		@Override
		public Connection getConnection() {
			throw new UnsupportedOperationException("these cases must not need phone_countries");
		}

		@Override
		public Connection getConnection(String username, String password) {
			return getConnection();
		}
	};

	private final LegacyPhoneNumbers numbers = new LegacyPhoneNumbers(new LegacyPhoneCountries(UNUSED));

	@ParameterizedTest
	@ValueSource(strings = {
			"01012345678", "(010) 1234-5678", "+20 10 1234 5678", "0020 10 1234 5678",
			"010 1234 5678", "010-1234-5678", "010.1234.5678", "010/1234/5678", " 01012345678 ",
			"1012345678", "201012345678", "+201012345678", "00201012345678", "+20 (0) 10 1234 5678",
			"٠١٠١٢٣٤٥٦٧٨", "۰۱۰۱۲۳۴۵۶۷۸", "０１０１２３４５６７８", "＋２０ １０ １２３４ ５６７８", "0101234567٨",
			"\t01012345678\n"})
	void everyHumanSpellingOfAnEgyptianMobileIsOneNumber(String input) {
		CanonicalPhone phone = CanonicalPhones.parse(input, null).orElseThrow();

		assertThat(phone.e164()).isEqualTo("+201012345678");
		assertThat(phone.region()).isEqualTo("EG");
		assertThat(phone.nationalDigits()).isEqualTo("01012345678");
		assertThat(phone.dialCode()).isEqualTo("+20");
		assertThat(phone.mobile()).isTrue();
	}

	@Test
	void aJsonNumberReadsAsPhpWouldCastIt() {
		// PHP's (string) of this float is "1012345678": the leading zero was
		// lost to the number, and the metadata still reads the mobile.
		assertThat(CanonicalPhones.parse(1.01234567800001E9, null).map(CanonicalPhone::e164))
				.contains("+201012345678");
		assertThat(CanonicalPhones.parse(1012345678L, "+20").map(CanonicalPhone::e164))
				.contains("+201012345678");
	}

	@Test
	void everyEgyptianMobileRangeIsValidAndNothingElseIs() {
		for (String prefix : List.of("010", "011", "012", "015")) {
			assertThat(CanonicalPhones.parse(prefix + "12345678", null)).as(prefix).isPresent();
		}
		for (String prefix : List.of("013", "014", "016", "017", "018", "019")) {
			assertThat(CanonicalPhones.parse(prefix + "12345678", null)).as(prefix).isEmpty();
		}
	}

	@Test
	void anEgyptianLandlineIsANumberButNotOneAnAccountMayHold() {
		// Cairo, 02 + eight digits. Valid to the metadata, so a lookup can read
		// it; legacy's rules never accepted a landline for an account, and
		// forAccount keeps that.
		CanonicalPhone cairo = CanonicalPhones.parse("02 2345 6789", null).orElseThrow();
		assertThat(cairo.e164()).isEqualTo("+20223456789");
		assertThat(cairo.mobile()).isFalse();
		assertThat(this.numbers.forAccount("0223456789", "+20")).isEmpty();
		assertThat(this.numbers.forAccount("+20 2 2345 6789", null)).isEmpty();
	}

	@Test
	void aSaudiNumberIsReadInItsCountryNationallyAndInAnyInternationally() {
		assertThat(CanonicalPhones.parse("0501234567", "+966").map(CanonicalPhone::e164)).contains("+966501234567");
		assertThat(CanonicalPhones.parse("501234567", "966").map(CanonicalPhone::e164)).contains("+966501234567");
		assertThat(CanonicalPhones.parse("966501234567", "+966").map(CanonicalPhone::e164)).contains("+966501234567");
		// International input carries its own country, whatever the context.
		for (String international : List.of("+966 50 123 4567", "00966501234567", "+966501234567")) {
			assertThat(CanonicalPhones.parse(international, null).map(CanonicalPhone::e164))
					.as(international).contains("+966501234567");
			assertThat(CanonicalPhones.parse(international, "+20").map(CanonicalPhone::e164))
					.as(international).contains("+966501234567");
		}
		CanonicalPhone saudi = CanonicalPhones.parse("+966501234567", null).orElseThrow();
		assertThat(saudi.nationalDigits()).isEqualTo("0501234567");
		assertThat(saudi.dialCode()).isEqualTo("+966");
		assertThat(saudi.region()).isEqualTo("SA");
	}

	@Test
	void emiratiAndLibyanNumbersAreReadInTheirOwnCountries() {
		assertThat(CanonicalPhones.parse("050 123 4567", "+971").map(CanonicalPhone::e164)).contains("+971501234567");
		assertThat(CanonicalPhones.parse("0912345678", "+218").map(CanonicalPhone::e164)).contains("+218912345678");
		assertThat(CanonicalPhones.parse("912345678", "+218").map(CanonicalPhone::nationalDigits)).contains("0912345678");
		// Legacy's hand-written UAE rule refused 051; so does the metadata.
		assertThat(CanonicalPhones.parse("0511234567", "+971")).isEmpty();
	}

	@Test
	void aNationalNumberWithNoContextIsEgyptianAndNothingElseIsGuessed() {
		// 0501234567 is a Saudi and an Emirati mobile, and also an Egyptian
		// (Mansoura) landline. With no country it is read as Egyptian -- the
		// owner's default -- never as whichever foreign number it could be.
		assertThat(CanonicalPhones.parse("0501234567", null).map(CanonicalPhone::e164)).contains("+20501234567");
		// A Saudi mobile that is no Egyptian number at all is simply invalid.
		assertThat(CanonicalPhones.parse("0561234567", null)).isEmpty();
		assertThat(CanonicalPhones.parse("966501234567", null)).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"", " ", "+", "0", "abc", "0101234567", "010123456789", "01312345678", "+2010123456789",
			"010ABCDEFGH", "01O12345678", "tel:+201012345678", "01012345678 ext 5", "01012345678;9",
			"010 1234 5678 x", "01012345678#", "010!1234!5678", "⓪①⓪①②③④⑤⑥⑦⑧", "⓿➀⓿➀➁➂➃➄➅➆➇",
			"0101234567➇", "〇〡〇〡〢〣〤〥〦〧〨", "⁰¹⁰¹²³⁴⁵⁶⁷⁸", "0101234567⑧", "Ⅰ012345678"})
	void anythingThatIsNotAWholeValidNumberIsRefused(String input) {
		assertThat(CanonicalPhones.parse(input, null)).as(input).isEmpty();
	}

	@Test
	void nullAndStructuredValuesAreRefused() {
		assertThat(CanonicalPhones.parse(null, null)).isEmpty();
		assertThat(CanonicalPhones.parse(List.of("01012345678"), null)).isEmpty();
		assertThat(CanonicalPhones.parse(Map.of("phone", "01012345678"), null)).isEmpty();
		assertThat(CanonicalPhones.parse(true, null)).isEmpty();
	}

	@Test
	void aContextTheMetadataDoesNotKnowValidatesNoNationalNumber() {
		assertThat(CanonicalPhones.parse("01012345678", "+999")).isEmpty();
		assertThat(CanonicalPhones.parse("01012345678", "abc")).isEmpty();
		// International input does not depend on the context at all.
		assertThat(CanonicalPhones.parse("+201012345678", "+999").map(CanonicalPhone::e164))
				.contains("+201012345678");
	}

	@Test
	void dialCodesNameRegions() {
		assertThat(CanonicalPhones.regionForDialCode("+20")).contains("EG");
		assertThat(CanonicalPhones.regionForDialCode("20")).contains("EG");
		assertThat(CanonicalPhones.regionForDialCode("0020")).contains("EG");
		assertThat(CanonicalPhones.regionForDialCode(" +966 ")).contains("SA");
		assertThat(CanonicalPhones.regionForDialCode("+1")).contains("US");
		assertThat(CanonicalPhones.regionForDialCode("+999")).isEmpty();
		assertThat(CanonicalPhones.regionForDialCode("+800")).isEmpty();
		assertThat(CanonicalPhones.regionForDialCode("")).isEmpty();
		assertThat(CanonicalPhones.regionForDialCode(null)).isEmpty();
		assertThat(CanonicalPhones.regionForDialCode("+2020")).isEmpty();
	}

	@Test
	void theStoredSpellingsAreTheMetadatasNotAHandWrittenList() {
		assertThat(CanonicalPhones.parse("01012345678", null).orElseThrow().storedSpellings())
				.containsExactly("01012345678", "1012345678", "201012345678", "+201012345678", "00201012345678");
		assertThat(CanonicalPhones.parse("0501234567", "+966").orElseThrow().storedSpellings())
				.containsExactly("0501234567", "501234567", "966501234567", "+966501234567", "00966501234567");
		// Every spelling fits the varchar(20) phone column.
		assertThat(CanonicalPhones.parse("0912345678", "+218").orElseThrow().storedSpellings())
				.allMatch(spelling -> spelling.length() <= 20);
	}

	@Test
	void anAccountMayHoldAMobileInAnOfferedCountryAndStoresItsOwnDialCode() {
		CanonicalPhone egyptian = this.numbers.forAccount("+20 10 1234 5678", "+966").orElseThrow();
		// International input wins over the request's code, and the stored
		// code is the number's own.
		assertThat(egyptian.dialCode()).isEqualTo("+20");
		assertThat(egyptian.nationalDigits()).isEqualTo("01012345678");

		assertThat(this.numbers.forAccount("0501234567", "+966").map(CanonicalPhone::e164)).contains("+966501234567");
		assertThat(this.numbers.forAccount("0501234567", "+971").map(CanonicalPhone::e164)).contains("+971501234567");
		// Legacy's hand-written Saudi rule took any 05 number; the metadata
		// knows 052 is not a Saudi mobile range.
		assertThat(this.numbers.forAccount("0521234567", "+966")).isEmpty();
	}

	@Test
	void theProductsCountriesAreOfferedWithoutATable() {
		// Egypt, Saudi Arabia and the UAE are what PHP's fallback validator
		// accepted whatever phone_countries held, so no read is needed for them.
		assertThat(this.numbers.forAccount("01012345678", null)).isPresent();
		assertThat(this.numbers.forAccount("0501234567", "+966")).isPresent();
		assertThat(this.numbers.forAccount("0501234567", "+971")).isPresent();
	}

	@Test
	void aLookupWrittenInternationallyHasOneReadingAndReadsNoTable() {
		PhoneLookup lookup = PhoneLookup.ofInput("+966 50 123 4567", () -> {
			throw new AssertionError("an international number must not read phone_countries");
		});

		assertThat(lookup.e164s()).containsExactly("+966501234567");
		assertThat(lookup.only().map(CanonicalPhone::e164)).contains("+966501234567");
	}

	@Test
	void aNationalLookupReadsEveryOfferedCountryAndARowDecidesWhichItIs() {
		PhoneLookup lookup = PhoneLookup.ofInput("0501234567", () -> List.of("+20", "+966", "+971", "+218"));

		// Egypt first (the landline), then each offered country it is valid in.
		assertThat(lookup.e164s()).containsExactly("+20501234567", "+966501234567", "+971501234567");
		assertThat(lookup.only()).isEmpty();
		// The Saudi account stored nationally, with or without its zero.
		assertThat(lookup.matches("0501234567", "+966")).isTrue();
		assertThat(lookup.matches("501234567", "+966")).isTrue();
		assertThat(lookup.matches("966501234567", "+966")).isTrue();
		// An Egyptian row with the same digits is the Egyptian reading.
		assertThat(lookup.matches("0501234567", "+20")).isTrue();
		assertThat(lookup.matches("0501234567", null)).isTrue();
		// A row that is a different number is not this one.
		assertThat(lookup.matches("0561234567", "+966")).isFalse();
		// A row in a country outside the readings cannot be reached.
		assertThat(lookup.matches("0501234567", "+1")).isFalse();
	}

	@Test
	void aKnownNumberMatchesEveryStoredSpellingOfItAndNothingElse() {
		PhoneLookup lookup = PhoneLookup.of(CanonicalPhones.parse("01012345678", null).orElseThrow());

		for (String stored : List.of("01012345678", "1012345678", "201012345678", "+201012345678")) {
			assertThat(lookup.matches(stored, "+20")).as(stored).isTrue();
			assertThat(lookup.matches(stored, null)).as(stored).isTrue();
		}
		assertThat(lookup.matches("01012345678", "+966")).isFalse();
		assertThat(lookup.matches("01099999999", "+20")).isFalse();
		assertThat(lookup.matches(null, "+20")).isFalse();
		assertThat(lookup.matches("", null)).isFalse();
	}

	@Test
	void aLookupOfNothingValidQueriesNothing() {
		PhoneLookup lookup = PhoneLookup.ofInput("⓪①⓪①②③④⑤⑥⑦⑧", List::of);

		assertThat(lookup.isEmpty()).isTrue();
		assertThat(lookup.e164s()).isEmpty();
		assertThat(lookup.clause("phone")).isEqualTo(new PhoneLookup.Clause("0=1", List.of()));
	}

	@Test
	void theClauseBindsStoredSpellingsNeverTheInput() {
		PhoneLookup.Clause clause = PhoneLookup.ofInput("(010) 1234-5678", List::of).clause("e.phone");

		assertThat(clause.sql()).isEqualTo("e.phone IN (?, ?, ?, ?, ?)");
		assertThat(clause.binds()).containsExactly(
				"01012345678", "1012345678", "201012345678", "+201012345678", "00201012345678");
	}

	@Test
	void oneRowOfSeveralIsTheOneStoredExactlyAsTypedOrNone() {
		Map<String, Object> national = Map.of("id", 1L, "phone", "01012345678");
		Map<String, Object> bare = Map.of("id", 2L, "phone", "1012345678");

		assertThat(PhoneLookup.singleRow(List.of(national), "+20 10 1234 5678")).isSameAs(national);
		assertThat(PhoneLookup.singleRow(List.of(national, bare), "010-1234-5678")).isSameAs(national);
		assertThat(PhoneLookup.singleRow(List.of(national, bare), "١٠١٢٣٤٥٦٧٨")).isSameAs(bare);
		assertThat(PhoneLookup.singleRow(List.of(national, bare), "+201012345678")).isNull();
		assertThat(PhoneLookup.singleRow(List.of(), "01012345678")).isNull();
	}

	@Test
	void aParseNeverThrowsForHostileInput() {
		assertThat(CanonicalPhones.parse("9".repeat(10_000), null)).isEmpty();
		assertThat(CanonicalPhones.parse("+" + "0".repeat(300), null)).isEmpty();
		assertThat(CanonicalPhones.parse("\u0000\u0000", null)).isEqualTo(Optional.empty());
	}
}
