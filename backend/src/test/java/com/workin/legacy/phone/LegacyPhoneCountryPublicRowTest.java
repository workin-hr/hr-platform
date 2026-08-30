package com.workin.legacy.phone;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@code phone_country_public_row()}'s two easy-to-miss rules: PHP's
 * {@code trim()} is narrower than Java's, and {@code ?:} tests falsiness
 * rather than emptiness.
 */
class LegacyPhoneCountryPublicRowTest {

	private static LegacyPhoneCountry country(String nameAr, String nameEn) {
		return new LegacyPhoneCountry(1, "+20", nameAr, nameEn, "🇪🇬", 11, "[\"010\"]", 1, 1);
	}

	/**
	 * {@code trim($chosen) ?: $fallback} is an elvis, so the literal string
	 * {@code "0"} is falsy and takes the fallback exactly as an empty string
	 * does.
	 *
	 * <p><b>But the fallback is {@code $row['name_ar'] ?? $row['name_en']}, not
	 * "the other language".</b> So an <em>English</em> request against a row
	 * whose {@code name_en} is {@code "0"} falls back to the Arabic name, while
	 * an <em>Arabic</em> request against a row whose {@code name_ar} is
	 * {@code "0"} falls back to {@code name_ar} again and answers {@code "0"}.
	 * The falsiness only changes the answer in one direction.
	 */
	@Test
	void theLiteralZeroIsFalsyButTheFallbackStillPrefersNameAr() {
		assertThat(LegacyPhoneCountryPublicRow.localizedName(country("مصر", "0"), "en"))
				.as("English picks \"0\", which is falsy, and the fallback reaches name_ar")
				.isEqualTo("مصر");
		assertThat(LegacyPhoneCountryPublicRow.localizedName(country("0", "Egypt"), "ar"))
				.as("Arabic picks \"0\", which is falsy -- and the fallback is name_ar, "
						+ "which is \"0\" again, so English is never reached")
				.isEqualTo("0");
	}

	/**
	 * PHP's {@code trim()} strips only {@code " \t\n\r\0\x0B"}. A form feed
	 * (0x0C) survives it, while Java's {@code String.trim()} removes every
	 * character at or below U+0020 -- so a name of a single form feed is
	 * non-empty in legacy and is returned as-is.
	 */
	@Test
	void aFormFeedSurvivesPhpTrimAndIsNotTreatedAsEmpty() {
		assertThat(LegacyPhoneCountryPublicRow.localizedName(country("\f", "Egypt"), "ar"))
				.as("PHP keeps the form feed, so the Arabic name is non-empty and wins")
				.isEqualTo("\f");
		assertThat("\f".trim())
				.as("and this is what Java would have done instead")
				.isEmpty();
	}

	/**
	 * A blank {@code name_ar} does <b>not</b> reach {@code name_en} for an
	 * Arabic request, for the same reason: the fallback re-reads
	 * {@code name_ar} through {@code ??}, which skips only null. A blank string
	 * is not null, so it wins again and the result is empty.
	 */
	@Test
	void aBlankNameArAnswersEmptyForArabicRatherThanFallingToEnglish() {
		assertThat(LegacyPhoneCountryPublicRow.localizedName(country("   ", "Egypt"), "ar"))
				.isEmpty();
		assertThat(LegacyPhoneCountryPublicRow.localizedName(country(null, "Egypt"), "ar"))
				.as("a NULL name_ar, by contrast, is skipped by ?? and English is used")
				.isEqualTo("Egypt");
	}

	/** The header prefix is matched after the same narrow trim. */
	@Test
	void theAcceptLanguageHeaderIsTrimmedWithPhpSemantics() {
		assertThat(LegacyPhoneCountryPublicRow.localizedName(country("مصر", "Egypt"), "  en-US  "))
				.isEqualTo("Egypt");
		assertThat(LegacyPhoneCountryPublicRow.localizedName(country("مصر", "Egypt"), "\fen"))
				.as("a form feed survives, so the value no longer starts with 'en'")
				.isEqualTo("مصر");
	}
}
