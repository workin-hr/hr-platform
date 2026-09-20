package com.workin.backend.platformadmin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.workin.backend.platformadmin.PlatformAdminCompanyDirectory.CompanyDetail;

/** The formatting {@code companies/detail.php} applies to its card and its employees table. */
class CompanyDetailTest {

	@Test
	void theEmployeesTableCountsTheRestOnlyPastFifteen() {
		// detail.php:82: `if (count($employees) > 15)`, then `count($employees) - 15`.
		assertThat(detail(null, 15).moreEmployees()).isZero();
		assertThat(detail(null, 16).moreEmployees()).isEqualTo(1);
		assertThat(detail(null, 40).moreEmployees()).isEqualTo(25);
		assertThat(detail(null, 0).moreEmployees()).isZero();
	}

	@Test
	void theCardShowsLegacysBlanks() {
		CompanyDetail.Profile none = new CompanyDetail.Profile("0100", null, false, "2026-01-02 10:11:12", " ", null);
		assertThat(none.emailLabel()).as("`?? '—'`: a dash for no email").isEqualTo("—");
		assertThat(new CompanyDetail.Profile("0100", "", false, null, null, null).emailLabel())
				.as("and an empty one printed as it is").isEmpty();
		assertThat(none.registeredOn()).isEqualTo("2026-01-02");
		assertThat(none.hasLogo()).isFalse();
		assertThat(new CompanyDetail.Profile("0100", null, false, null, "//evil.example/p.png", null).hasLogo())
				.as("a logo naming another origin is not loaded either").isFalse();
		assertThat(new CompanyDetail.Profile("0100", null, false, null, "/uploads/logos/a.png", null).logoSrc())
				.isEqualTo("/uploads/logos/a.png");
		assertThat(none.commercialRegHref()).isNull();
		assertThat(new CompanyDetail.Profile("0100", null, false, null, null, "javascript:alert(1)").commercialRegHref())
				.isNull();
		assertThat(new CompanyDetail.Profile("0100", null, false, null, null, "0").commercialRegHref())
				.as("detail.php:50 gates the button on PHP truthiness, and \"0\" is falsy there").isNull();
		assertThat(new CompanyDetail.Profile("0100", null, false, null, null, "//evil.example/r.pdf").commercialRegHref())
				.as("another origin, named without a scheme").isNull();
		assertThat(new CompanyDetail.Profile("0100", null, false, null, null, "https://f.example/r.pdf").commercialRegHref())
				.isEqualTo("https://f.example/r.pdf");
		assertThat(detail(null, 0).avatarName()).as("company_logo_src()'s `?: 'C'`").isEqualTo("C");
		assertThat(detail(" Acme ", 0).avatarName()).isEqualTo("Acme");
	}

	@Test
	void anEmployeeRowShowsLegacysDashes() {
		CompanyDetail.Employee blank = new CompanyDetail.Employee(7L, "7", " ", null, null, null, true);
		assertThat(blank.nameLabel()).isEqualTo("—");
		assertThat(blank.branchLabel()).as("`?? '—'`").isEqualTo("—");
		assertThat(blank.hireDateLabel()).isEqualTo("—");
		CompanyDetail.Employee full = new CompanyDetail.Employee(8L, "A-1", "Aya Alpha", "0101", "HQ",
				"2024-02-03", true);
		assertThat(full.nameLabel()).isEqualTo("Aya Alpha");
		assertThat(full.branchLabel()).isEqualTo("HQ");
		assertThat(full.hireDateLabel()).isEqualTo("2024-02-03");
	}

	private static CompanyDetail detail(String name, long totalEmployees) {
		return new CompanyDetail(new PlatformAdminCompanyDirectory.CompanyView(1L, name, "active"), null, null,
				0L, totalEmployees, 0L, 0L, 0L, List.of(), List.of(), List.of());
	}

}
