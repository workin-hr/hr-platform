package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.workin.legacy.phone.LegacyPhoneCountry;

/**
 * The country select and the phone rules a company or employee form gets, from the active
 * {@code phone_countries} rows (D-261): {@code phone_country_codes_for_select()} labelled by
 * {@code phone_country_option_label()}, and {@code phone_countries_validation_rules_for_js()}.
 */
class PhoneCountryChoicesTest {

	private static final LegacyPhoneCountry EGYPT = new LegacyPhoneCountry(1, "+20", "مصر", "Egypt", "🇪🇬",
			11, "[\"010\",\"011\"]", 1, 1);

	private static final LegacyPhoneCountry SAUDI = new LegacyPhoneCountry(2, "+966", "السعودية", "Saudi Arabia",
			"🇸🇦", 10, "05, 5", 2, 1);

	@Test
	void eachActiveCountryIsAnOptionLabelledByFlagNameAndCode() {
		assertThat(PhoneCountryChoices.of(List.of(EGYPT, SAUDI), "ar").options()).containsExactly(
				new PhoneCountryChoices.Option("+20", "🇪🇬 مصر (+20)"),
				new PhoneCountryChoices.Option("+966", "🇸🇦 السعودية (+966)"));
		assertThat(PhoneCountryChoices.of(List.of(EGYPT, SAUDI), "en").options())
				.extracting(PhoneCountryChoices.Option::label)
				.containsExactly("🇪🇬 Egypt (+20)", "🇸🇦 Saudi Arabia (+966)");
	}

	@Test
	void aBlankNameFallsBackToTheArabicOneAndABlankFlagLeavesNoGap() {
		// phone_country_localized_name(): `trim($name) ?: trim($row['name_ar'])`, and
		// phone_country_option_label() trims the joined label.
		LegacyPhoneCountry unnamed = new LegacyPhoneCountry(3, "+218", " ليبيا ", "  ", "", 10, null, 3, 1);

		assertThat(PhoneCountryChoices.of(List.of(unnamed), "en").options())
				.containsExactly(new PhoneCountryChoices.Option("+218", "ليبيا (+218)"));
	}

	@Test
	void theRulesAreEachCodesLengthAndDecodedPrefixesAsJson() {
		// A JSON list and a delimited string both decode, as phone_country_decode_prefixes()
		// does; "05" and "5" stay two prefixes, and no prefixes is an empty list.
		LegacyPhoneCountry none = new LegacyPhoneCountry(3, "+218", "ليبيا", "Libya", "", 10, null, 3, 1);

		assertThat(PhoneCountryChoices.of(List.of(EGYPT, SAUDI, none), "ar").rules()).isEqualTo(
				"{\"+20\":{\"phone_length\":11,\"phone_prefixes\":[\"010\",\"011\"]},"
						+ "\"+966\":{\"phone_length\":10,\"phone_prefixes\":[\"05\",\"5\"]},"
						+ "\"+218\":{\"phone_length\":10,\"phone_prefixes\":[]}}");
	}

	@Test
	void aBlankCodeIsNeitherAnOptionNorARuleAndNoCountriesIsAnEmptyRuleSet() {
		LegacyPhoneCountry blank = new LegacyPhoneCountry(4, "", "بلا", "None", "", 9, null, 4, 1);

		PhoneCountryChoices choices = PhoneCountryChoices.of(List.of(blank, EGYPT), "ar");
		assertThat(choices.options()).extracting(PhoneCountryChoices.Option::code).containsExactly("+20");
		assertThat(choices.rules()).doesNotContain("\"\"");
		assertThat(PhoneCountryChoices.of(List.of(), "ar").rules())
				.as("rules, and so the scripts, even with no country: the browser then refuses every number, as legacy's does")
				.isEqualTo("{}");
	}

	@Test
	void theSelectOffersOnlyItsOwnCodes() {
		PhoneCountryChoices choices = PhoneCountryChoices.of(List.of(EGYPT), "ar");

		assertThat(choices.offers("+20")).isTrue();
		assertThat(choices.offers("+966")).as("a retired country's code").isFalse();
		assertThat(PhoneCountryChoices.NONE.offers("+20")).isFalse();
		assertThat(PhoneCountryChoices.NONE.rules()).as("no rules, so the layout loads no phone script").isNull();
	}
}
