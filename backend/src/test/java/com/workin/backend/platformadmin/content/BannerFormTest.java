package com.workin.backend.platformadmin.content;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the banner action rules, which are a security control rather than
 * formatting.
 *
 * <p>{@code banners/list.php} returns {@code button_action_value} to the
 * clients unsanitised -- the API's own sanitizers are defined and called
 * from nowhere -- and the clients open it. Write-time validation is the
 * whole of the control, so each of these cases is the difference between a
 * value reaching a customer's device and not.
 */
class BannerFormTest {

	private static String value(Banner.Action action, String raw) {
		return BannerForm.resolveActionValue(action, raw, null, null);
	}

	@Test
	void anExternalUrlMustBeHttpOrHttps() {
		assertThat(value(Banner.Action.EXTERNAL_URL, "https://example.com")).isEqualTo("https://example.com");
		assertThat(value(Banner.Action.EXTERNAL_URL, "http://example.com")).isEqualTo("http://example.com");
		assertThat(value(Banner.Action.EXTERNAL_URL, "HTTPS://example.com")).isEqualTo("HTTPS://example.com");
	}

	/** The case the scheme check exists for. */
	@Test
	void aJavascriptUrlIsNotStored() {
		assertThat(value(Banner.Action.EXTERNAL_URL, "javascript:alert(1)")).isNull();
		assertThat(value(Banner.Action.EXTERNAL_URL, "data:text/html,<script>")).isNull();
		assertThat(value(Banner.Action.EXTERNAL_URL, "file:///etc/passwd")).isNull();
		assertThat(value(Banner.Action.EXTERNAL_URL, "//example.com")).isNull();
	}

	@Test
	void anInternalRouteMustBeOnTheAllowlist() {
		assertThat(value(Banner.Action.INTERNAL_ROUTE, "employees")).isEqualTo("employees");
		assertThat(value(Banner.Action.INTERNAL_ROUTE, "payroll")).isEqualTo("payroll");
		assertThat(value(Banner.Action.INTERNAL_ROUTE, "not_a_screen")).isNull();
		assertThat(value(Banner.Action.INTERNAL_ROUTE, "../../etc/passwd")).isNull();
	}

	@Test
	void anInternalRouteIsLowerCasedBeforeTheCheck() {
		assertThat(value(Banner.Action.INTERNAL_ROUTE, "Employees")).isEqualTo("employees");
		assertThat(value(Banner.Action.INTERNAL_ROUTE, "  PAYROLL  ")).isEqualTo("payroll");
	}

	/** The allowlist is the desktop client's screen list; a drift makes a dead button. */
	@Test
	void theAllowlistMatchesTheDesktopScreenKeys() {
		assertThat(Banner.INTERNAL_ROUTES)
				.hasSize(20)
				.contains("home", "dashboard", "app_terms", "app_compliance")
				.doesNotHaveDuplicates();
	}

	@Test
	void noneStoresNothingWhateverWasTyped() {
		assertThat(value(Banner.Action.NONE, "https://example.com")).isNull();
	}

	@Test
	void anUnknownActionTypeBecomesNone() {
		assertThat(Banner.Action.of("sql")).isEqualTo(Banner.Action.NONE);
		assertThat(Banner.Action.of(null)).isEqualTo(Banner.Action.NONE);
	}

	@Test
	void aWhatsappNumberIsTheDialCodeWithoutItsPlusFollowedByDigits() {
		assertThat(BannerForm.whatsappNumber("+20", "010 1234 5678")).isEqualTo("2001012345678");
		assertThat(BannerForm.whatsappNumber("20", "0101234567")).isEqualTo("200101234567");
	}

	@Test
	void anEmptyWhatsappLocalNumberIsNoNumberRatherThanABareDialCode() {
		assertThat(BannerForm.whatsappNumber("+20", "")).isNull();
		assertThat(BannerForm.whatsappNumber("+20", "   ")).isNull();
		assertThat(BannerForm.whatsappNumber("+20", "abc")).isNull();
	}

	@Test
	void aBannerWithoutAnImageIsRejected() {
		assertThat(BannerForm.validate(null, "t", "t", null, null, null, null,
				"both", "none", null, null, null, true, "0").errorKey())
				.isEqualTo("banner_image_required");
		assertThat(BannerForm.validate("   ", "t", "t", null, null, null, null,
				"both", "none", null, null, null, true, "0").errorKey())
				.isEqualTo("banner_image_required");
	}

	/**
	 * A failed action value keeps its type and stores null -- a button with
	 * nothing behind it, rather than a button pointing somewhere unchecked.
	 */
	@Test
	void aRejectedActionValueLeavesTheTypeIntactAndTheValueNull() {
		BannerForm.Result result = BannerForm.validate("/uploads/banners/x.png", "t", "t",
				null, null, null, null, "both", "external_url", "javascript:alert(1)",
				null, null, true, "0");

		assertThat(result.ok()).isTrue();
		assertThat(result.banner().buttonActionType()).isEqualTo(Banner.Action.EXTERNAL_URL);
		assertThat(result.banner().buttonActionValue()).isNull();
	}

	@Test
	void blankTextFieldsAreStoredAsNullNotEmptyStrings() {
		BannerForm.Result result = BannerForm.validate("/uploads/banners/x.png", "  ", "T",
				null, null, null, null, "mobile", "none", null, null, null, false, "3");

		assertThat(result.banner().titleAr()).isNull();
		assertThat(result.banner().titleEn()).isEqualTo("T");
		assertThat(result.banner().platform()).isEqualTo(Faq.Platform.MOBILE);
		assertThat(result.banner().sortOrder()).isEqualTo(3);
		assertThat(result.banner().active()).isFalse();
	}

}
