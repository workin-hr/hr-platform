package com.workin.backend.platformadmin.org;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The branch radius and a department's branch ids, read with PHP 8's {@code (int)} cast. */
class OrgFormCastTest {

	@Test
	void theRadiusIsCastAsPhpCastsItThenDefaultedAndCapped() {
		assertThat(Branch.radiusMeters("1.5e2")).as("(int) \"1.5e2\" is 150, not 1").isEqualTo(150);
		assertThat(Branch.radiusMeters(" 300m")).isEqualTo(300);
		assertThat(Branch.radiusMeters("abc")).as("0, then legacy's default").isEqualTo(200);
		assertThat(Branch.radiusMeters("9e9")).as("past the int range, then capped").isEqualTo(5000);
		assertThat(Branch.radiusMeters(null)).isEqualTo(200);
	}

	@Test
	void branchIdsAreCastAsPhpCastsThemAndOnlyPositiveOnesKept() {
		assertThat(Department.parseBranchIds(new String[] {"1e1", "3.7", "abc", "-1", "10", " 4"}))
				.as("(int) \"1e1\" is 10, the same branch as 10, kept once; its leading digit alone is 1")
				.containsExactly(10L, 3L, 4L);
	}

}
