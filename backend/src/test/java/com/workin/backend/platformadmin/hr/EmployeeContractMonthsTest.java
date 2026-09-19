package com.workin.backend.platformadmin.hr;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@code employee_parse_contract_months()} ({@code employee_helper.php:232-244}). */
class EmployeeContractMonthsTest {

	@Test
	void theDurationIsPhpsIntCastAndYearsAreStoredAsMonths() {
		assertThat(EmployeeAdminService.contractMonths("1e1", "months")).as("(int) reads the exponent").isEqualTo(10);
		assertThat(EmployeeAdminService.contractMonths(" 2.9 ", "years")).isEqualTo(24);
		assertThat(EmployeeAdminService.contractMonths("6abc", "months")).isEqualTo(6);
	}

	@Test
	void aBlankOrNonPositiveDurationIsNone() {
		assertThat(EmployeeAdminService.contractMonths("", "months")).isNull();
		assertThat(EmployeeAdminService.contractMonths("abc", "months")).isNull();
		assertThat(EmployeeAdminService.contractMonths("-3", "years")).isNull();
		assertThat(EmployeeAdminService.contractMonths(null, "months")).isNull();
	}

	@Test
	void aDurationPastTheIntColumnIsStoredAtItsBound() {
		assertThat(EmployeeAdminService.contractMonths("3000000000", "months")).isEqualTo(Integer.MAX_VALUE);
		assertThat(EmployeeAdminService.contractMonths("300000000", "years"))
				.as("3.6e9 months does not wrap negative").isEqualTo(Integer.MAX_VALUE);
		assertThat(EmployeeAdminService.contractMonths("99999999999999999999", "years")).isEqualTo(Integer.MAX_VALUE);
	}

}
