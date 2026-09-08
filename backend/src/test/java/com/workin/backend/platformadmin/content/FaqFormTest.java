package com.workin.backend.platformadmin.content;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the FAQ validation to {@code faq_category_validate_post()} and
 * {@code faq_item_validate_post()}.
 *
 * <p>The both-languages rule is the one worth holding: the clients read
 * whichever column matches their locale and do not fall back, so an item
 * saved with only Arabic renders to an English user as a blank panel
 * rather than being hidden.
 */
class FaqFormTest {

	@Test
	void aCategoryNeedsBothNames() {
		assertThat(FaqForm.validateCategory("عام", "General", "0", true).ok()).isTrue();
		assertThat(FaqForm.validateCategory("", "General", "0", true).errorKey()).isEqualTo("error_required");
		assertThat(FaqForm.validateCategory("عام", "  ", "0", true).errorKey()).isEqualTo("error_required");
	}

	@Test
	void aCategorysSortOrderDefaultsToZeroWhenUnparsable() {
		assertThat(FaqForm.validateCategory("عام", "General", "abc", true).category().sortOrder()).isZero();
		assertThat(FaqForm.validateCategory("عام", "General", "", true).category().sortOrder()).isZero();
		assertThat(FaqForm.validateCategory("عام", "General", "7", true).category().sortOrder()).isEqualTo(7);
	}

	private static FaqForm.ItemResult item(String categoryId, String qAr, String qEn, String aAr, String aEn) {
		return FaqForm.validateItem(categoryId, qAr, qEn, aAr, aEn, "both", "0", true);
	}

	@Test
	void anItemNeedsACategoryAndAllFourTexts() {
		assertThat(item("1", "س", "Q", "ج", "A").ok()).isTrue();
		assertThat(item("0", "س", "Q", "ج", "A").errorKey()).isEqualTo("error_required");
		assertThat(item("1", "", "Q", "ج", "A").errorKey()).isEqualTo("error_required");
		assertThat(item("1", "س", "", "ج", "A").errorKey()).isEqualTo("error_required");
		assertThat(item("1", "س", "Q", "", "A").errorKey()).isEqualTo("error_required");
		assertThat(item("1", "س", "Q", "ج", " ").errorKey()).isEqualTo("error_required");
	}

	@Test
	void aNonNumericCategoryIdIsRejectedRatherThanThrowing() {
		assertThat(item("not-a-number", "س", "Q", "ج", "A").errorKey()).isEqualTo("error_required");
	}

	@Test
	void anUnknownPlatformFallsBackToBoth() {
		assertThat(FaqForm.validateItem("1", "س", "Q", "ج", "A", "watch", "0", true).item().platform())
				.isEqualTo(Faq.Platform.BOTH);
		assertThat(FaqForm.validateItem("1", "س", "Q", "ج", "A", null, "0", true).item().platform())
				.isEqualTo(Faq.Platform.BOTH);
	}

	@Test
	void aKnownPlatformIsKept() {
		assertThat(FaqForm.validateItem("1", "س", "Q", "ج", "A", "mobile", "0", true).item().platform())
				.isEqualTo(Faq.Platform.MOBILE);
		assertThat(Faq.Platform.MOBILE.stored()).isEqualTo("mobile");
	}

	@Test
	void textIsTrimmedBeforeStoring() {
		assertThat(item("1", "  س  ", " Q ", " ج ", " A ").item().questionAr()).isEqualTo("س");
	}

}
