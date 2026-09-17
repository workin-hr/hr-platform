package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** {@code org_list_read_filters()}'s {@code (int)} casts, as PHP 8 casts a query string. */
class DashboardListFiltersTest {

	private static DashboardListFilters read(String... parameters) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/branches");
		for (int at = 0; at < parameters.length; at += 2) {
			request.setParameter(parameters[at], parameters[at + 1]);
		}
		return DashboardListFilters.read(DashboardSession.admin(0L), request);
	}

	@Test
	void aNumberWithAnExponentOrTrailingTextIsCastAsPhpCastsIt() {
		DashboardListFilters filters = read("page", "1e2", "per_page", " 2e1", "filter_branch", "3abc",
				"filter_department", "4.9");
		assertThat(filters.page()).as("(int) \"1e2\" is 100, not 1").isEqualTo(100);
		assertThat(filters.perPage()).isEqualTo(20);
		assertThat(filters.filterBranch()).isEqualTo(3);
		assertThat(filters.filterDepartment()).isEqualTo(4);
	}

	@Test
	void aPageOrSizePastTheIntRangeIsClampedRatherThanWrapped() {
		// (int) 4294967297 wrapped to 1: PHP keeps the 64-bit value and min() caps it.
		DashboardListFilters filters = read("page", "4294967297", "per_page", "4294967297");
		assertThat(filters.page()).isEqualTo(Integer.MAX_VALUE);
		assertThat(filters.perPage()).isEqualTo(DashboardPage.SIZE_MAX);
	}

	@Test
	void anAbsentOrUnreadableValueIsLegacysDefault() {
		assertThat(read().page()).isEqualTo(1);
		assertThat(read().perPage()).isEqualTo(DashboardPage.SIZE_DEFAULT);
		DashboardListFilters unreadable = read("page", "abc", "per_page", "-5", "filter_branch", "-2");
		assertThat(unreadable.page()).isEqualTo(1);
		assertThat(unreadable.perPage()).isEqualTo(1);
		assertThat(unreadable.filterBranch()).isZero();
	}

}
