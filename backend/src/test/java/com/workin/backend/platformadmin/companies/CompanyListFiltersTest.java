package com.workin.backend.platformadmin.companies;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.workin.backend.platformadmin.web.DashboardPage;

/** {@code companies/page.php}'s {@code (int)} casts, as PHP 8 casts a query string. */
class CompanyListFiltersTest {

	private static CompanyListFilters read(String... parameters) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/companies");
		for (int at = 0; at < parameters.length; at += 2) {
			request.setParameter(parameters[at], parameters[at + 1]);
		}
		return CompanyListFilters.read(request);
	}

	@Test
	void aNumberWithAnExponentOrTrailingTextIsCastAsPhpCastsIt() {
		CompanyListFilters filters = read("page", "1e2", "per_page", "2.5e1", "filter_activity", "7abc",
				"filter_title", "\t8", "filter_size", "9e0");
		assertThat(filters.page()).isEqualTo(100);
		assertThat(filters.perPage()).isEqualTo(25);
		assertThat(filters.activityId()).isEqualTo(7);
		assertThat(filters.titleId()).isEqualTo(8);
		assertThat(filters.sizeId()).isEqualTo(9);
	}

	@Test
	void aPageOrSizePastTheIntRangeIsClampedRatherThanWrapped() {
		CompanyListFilters filters = read("page", "4294967297", "per_page", "4294967297");
		assertThat(filters.page()).isEqualTo(Integer.MAX_VALUE);
		assertThat(filters.perPage()).isEqualTo(DashboardPage.SIZE_MAX);
	}

	@Test
	void anAbsentOrUnreadableValueIsLegacysDefault() {
		assertThat(read().page()).isEqualTo(1);
		assertThat(read().perPage()).isEqualTo(DashboardPage.SIZE_DEFAULT);
		assertThat(read("page", "abc", "filter_activity", "-3").activityId()).isZero();
	}

}
