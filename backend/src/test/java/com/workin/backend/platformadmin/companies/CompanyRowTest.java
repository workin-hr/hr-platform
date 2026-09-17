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

	private static CompanyRow row(String name) {
		return new CompanyRow(1L, name, null, null, "—", "", null, null, null, "active", 0L, 0L, 0L, null);
	}

}
