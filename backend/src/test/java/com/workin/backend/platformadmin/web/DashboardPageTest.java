package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class DashboardPageTest {

	@Test
	void theOffsetOfTheLastIntPageIsTakenInALongAndDoesNotWrap() {
		// A page number reaches Integer.MAX_VALUE through PHP's cast of ?page=; in an int its
		// offset wrapped negative, and the database refuses a negative OFFSET.
		assertThat(DashboardPage.offsetFor(Integer.MAX_VALUE, 10)).isEqualTo(21_474_836_460L);
		assertThat(DashboardPage.offsetFor(Integer.MAX_VALUE, 200)).isEqualTo(429_496_729_200L);
	}

	@Test
	void aPagePastTheIntRangesOffsetReportsItsRangeFromThatOffsetAsDbPaginateDoes() {
		DashboardPage<String> page = DashboardPage.of(List.of(), 5, Integer.MAX_VALUE, 10);
		assertThat(page.page()).isEqualTo(1);
		assertThat(page.from()).isEqualTo(21_474_836_461L);
		assertThat(page.to()).isEqualTo(5L);
	}

}
