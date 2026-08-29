package com.workin.legacy.settings;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@code pick_label()}'s boundaries. Every case here is one a reasonable
 * simplification would get wrong.
 */
class LegacySettingLabelsTest {

	@Test
	void eachLocaleTakesItsOwnLabelWhenBothArePresent() {
		assertThat(LegacySettingLabels.pick("ar", "عربي", "English", "key")).isEqualTo("عربي");
		assertThat(LegacySettingLabels.pick("en", "عربي", "English", "key")).isEqualTo("English");
	}

	/** The fallback is the <em>other language</em>, not the supplied default. */
	@Test
	void aMissingLabelFallsBackToTheOtherLanguageBeforeTheDefault() {
		assertThat(LegacySettingLabels.pick("ar", null, "English", "key")).isEqualTo("English");
		assertThat(LegacySettingLabels.pick("en", "عربي", null, "key")).isEqualTo("عربي");
	}

	@Test
	void theSuppliedFallbackIsReachedOnlyWhenBothLabelsAreAbsent() {
		assertThat(LegacySettingLabels.pick("ar", null, null, "the_key")).isEqualTo("the_key");
		assertThat(LegacySettingLabels.pick("en", null, null, null)).isNull();
	}

	/**
	 * Null and blank are <b>not</b> the same input. The language pick uses
	 * {@code ??}, which skips only null -- so a blank Arabic label is chosen,
	 * then trims to empty, and lands on the fallback instead of on the English
	 * label a null would have selected.
	 */
	@Test
	void aBlankLabelIsChosenAndThenFallsPastTheOtherLanguage() {
		assertThat(LegacySettingLabels.pick("ar", "   ", "English", "the_key"))
				.as("blank is picked over English, then trims empty and reaches the fallback")
				.isEqualTo("the_key");
		assertThat(LegacySettingLabels.pick("ar", null, "English", "the_key"))
				.as("null, by contrast, skips to English")
				.isEqualTo("English");
	}

	/**
	 * The emptiness test uses PHP's trim set, which is narrower than Java's. A
	 * form feed (0x0C) survives {@code trim()} in PHP, so a label consisting of
	 * one is <b>non-blank</b> and is returned unchanged -- where Java's
	 * {@code String.trim()} would have emptied it into the fallback.
	 */
	@Test
	void aFormFeedOnlyLabelIsNonBlankToPhpAndIsReturned() {
		assertThat(LegacySettingLabels.pick("ar", "\f", "English", "the_key"))
				.isEqualTo("\f");
		assertThat("\f".trim())
				.as("and this is what Java's trim would have done instead")
				.isEmpty();
	}

	/** A chosen label is returned untrimmed -- only the emptiness test trims. */
	@Test
	void aChosenLabelKeepsItsSurroundingWhitespace() {
		assertThat(LegacySettingLabels.pick("en", null, "  English  ", "key")).isEqualTo("  English  ");
	}

	@Test
	void theDescriptionFallbackIsNullRatherThanTheSettingKey() {
		assertThat(LegacySettingLabels.descriptionFields("en", null, null))
				.containsEntry("description", null)
				.containsEntry("description_ar", null)
				.containsEntry("description_en", null);
		assertThat(LegacySettingLabels.descriptionFields("ar", "وصف", "Desc"))
				.containsEntry("description", "وصف");
	}
}
