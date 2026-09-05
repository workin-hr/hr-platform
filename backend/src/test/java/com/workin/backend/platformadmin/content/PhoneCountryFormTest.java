package com.workin.backend.platformadmin.content;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the validation to {@code dashboard_phone_country_validate_post()}.
 *
 * <p>These rules guard a table every mobile and desktop client reads at
 * startup to decide whether a phone number is valid, so a rule that drifts
 * from the dashboard's does not fail here -- it silently starts rejecting
 * real customers' numbers, or accepting numbers the clients then cannot
 * use.
 */
class PhoneCountryFormTest {

	private static PhoneCountryForm.Result validate(String code, String length, String prefixes) {
		return PhoneCountryForm.validate(code, "مصر", "Egypt", "🇪🇬", length, prefixes, true, "0");
	}

	@Test
	void acceptsAWellFormedCountry() {
		PhoneCountryForm.Result result = validate("+20", "10", "10, 11, 12");

		assertThat(result.ok()).isTrue();
		assertThat(result.country().countryCode()).isEqualTo("+20");
		assertThat(result.country().prefixes()).containsExactly("10", "11", "12");
	}

	@Test
	void rejectsADialCodeWithoutAPlus() {
		assertThat(validate("20", "10", "").errorKey()).isEqualTo("error_invalid_country");
	}

	@Test
	void rejectsADialCodeLongerThanFourDigits() {
		assertThat(validate("+12345", "10", "").errorKey()).isEqualTo("error_invalid_country");
	}

	@Test
	void rejectsANonNumericDialCode() {
		assertThat(validate("+2a", "10", "").errorKey()).isEqualTo("error_invalid_country");
	}

	@Test
	void requiresBothNames() {
		assertThat(PhoneCountryForm.validate("+20", " ", "Egypt", "", "10", "", true, "0").errorKey())
				.isEqualTo("error_required");
		assertThat(PhoneCountryForm.validate("+20", "مصر", "", "", "10", "", true, "0").errorKey())
				.isEqualTo("error_required");
	}

	@Test
	void boundsThePhoneLength() {
		assertThat(validate("+20", "3", "").errorKey()).isEqualTo("phone_length_invalid");
		assertThat(validate("+20", "16", "").errorKey()).isEqualTo("phone_length_invalid");
		assertThat(validate("+20", "4", "").ok()).isTrue();
		assertThat(validate("+20", "15", "").ok()).isTrue();
	}

	@Test
	void aNonNumericLengthIsZeroAndSoIsRejected() {
		assertThat(validate("+20", "ten", "").errorKey()).isEqualTo("phone_length_invalid");
	}

	/**
	 * A prefix as long as the number itself matches everything, which is the
	 * same as having no rule -- the dashboard rejects it rather than storing a
	 * rule that cannot discriminate.
	 */
	@Test
	void rejectsAPrefixNotShorterThanTheNumber() {
		assertThat(validate("+20", "10", "1234567890").errorKey()).isEqualTo("phone_prefix_invalid");
		assertThat(validate("+20", "4", "1234").errorKey()).isEqualTo("phone_prefix_invalid");
		assertThat(validate("+20", "4", "123").ok()).isTrue();
	}

	@Test
	void separatesPrefixesOnCommasAndWhitespaceAndDropsDuplicates() {
		assertThat(PhoneCountryForm.parsePrefixes("10, 11\n12  13,,10"))
				.containsExactly("10", "11", "12", "13");
	}

	@Test
	void anEmptyPrefixFieldIsNoPrefixes() {
		assertThat(PhoneCountryForm.parsePrefixes("   ")).isEmpty();
		assertThat(PhoneCountryForm.parsePrefixes(null)).isEmpty();
	}

	/** The column holds JSON, but rows the dashboard wrote years ago hold a bare list. */
	@Test
	void decodesBothStoredPrefixShapes() {
		assertThat(PhoneCountryStore.decodePrefixes("[\"10\",\"11\"]")).containsExactly("10", "11");
		assertThat(PhoneCountryStore.decodePrefixes("10,11")).containsExactly("10", "11");
		assertThat(PhoneCountryStore.decodePrefixes("")).isEmpty();
		assertThat(PhoneCountryStore.decodePrefixes(null)).isEmpty();
	}

	@Test
	void malformedStoredJsonReadsAsNoPrefixesRatherThanThrowing() {
		assertThat(PhoneCountryStore.decodePrefixes("[\"10\",")).isEmpty();
	}

	@Test
	void alwaysWritesJsonBack() {
		assertThat(PhoneCountryStore.encodePrefixes(List.of("10", "11"))).isEqualTo("[\"10\",\"11\"]");
		assertThat(PhoneCountryStore.encodePrefixes(null)).isEqualTo("[]");
	}

}
