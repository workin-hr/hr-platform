package com.workin.backend.platformadmin.companies;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CompanyRowTest {

	@Test
	void theAvatarIsDrawnFromTheNameOrLegacysCWhenPhpReadsTheNameAsEmpty() {
		// company_logo_src() (company_helper.php:173): `trim((string) $name) ?: 'C'`.
		assertThat(row(" Acme ").avatarName()).isEqualTo("Acme");
		assertThat(row(null).avatarName()).isEqualTo("C");
		assertThat(row("  ").avatarName()).isEqualTo("C");
		assertThat(row(" 0 ").avatarName()).as("PHP's ?: reads \"0\" as empty").isEqualTo("C");
		assertThat(row("00").avatarName()).isEqualTo("00");
	}

	@Test
	void theCommercialRegistrationLinksOnlyAWebAddressOrAPath() {
		// Legacy's list prefixes anything but http and https with its own host, so it never
		// links another scheme (dashboard_media_url(), includes/media.php:84-102).
		assertThat(withRegistration("/uploads/docs/reg.pdf").commercialRegHref()).isEqualTo("/uploads/docs/reg.pdf");
		assertThat(withRegistration(" https://files.example.test/reg.pdf ").commercialRegHref())
				.isEqualTo("https://files.example.test/reg.pdf");
		assertThat(withRegistration("javascript:alert(1)").commercialRegHref()).isNull();
		assertThat(withRegistration(" ").commercialRegHref()).isNull();
		assertThat(withRegistration(null).commercialRegHref()).isNull();
	}

	private static CompanyRow row(String name) {
		return new CompanyRow(1L, name, null, null, "—", "", null, null, null, "active", 0L, 0L, 0L, null);
	}

	private static CompanyRow withRegistration(String url) {
		return new CompanyRow(1L, "Acme", null, url, "—", "", null, null, null, "active", 0L, 0L, 0L, null);
	}

}
