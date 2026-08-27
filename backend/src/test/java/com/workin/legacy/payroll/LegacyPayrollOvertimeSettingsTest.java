package com.workin.legacy.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LegacyPayrollOvertimeSettingsTest {

	@Test
	void unsetOrNonPositiveDefaultsToOneTwoFive() {
		assertThat(LegacyPayrollOvertimeSettings.multiplierFromRaw(125)).isEqualTo(1.25);
		assertThat(LegacyPayrollOvertimeSettings.multiplierFromRaw(0)).isEqualTo(1.25);
		assertThat(LegacyPayrollOvertimeSettings.multiplierFromRaw(-5)).isEqualTo(1.25);
	}

	@Test
	void aValueOverTenIsTreatedAsAPercentage() {
		assertThat(LegacyPayrollOvertimeSettings.multiplierFromRaw(150)).isEqualTo(1.5);
		assertThat(LegacyPayrollOvertimeSettings.multiplierFromRaw(200)).isEqualTo(2.0);
	}

	@Test
	void aValueAtOrBelowTenIsAlreadyAMultiplier() {
		assertThat(LegacyPayrollOvertimeSettings.multiplierFromRaw(1.5)).isEqualTo(1.5);
		assertThat(LegacyPayrollOvertimeSettings.multiplierFromRaw(10)).isEqualTo(10.0);
	}

	@Test
	void paysOvertimeAcceptsTheFourTruthySpellingsCaseInsensitively() {
		assertThat(LegacyPayrollOvertimeSettings.paysOvertimeFromRaw("1")).isTrue();
		assertThat(LegacyPayrollOvertimeSettings.paysOvertimeFromRaw("TRUE")).isTrue();
		assertThat(LegacyPayrollOvertimeSettings.paysOvertimeFromRaw(" yes ")).isTrue();
		assertThat(LegacyPayrollOvertimeSettings.paysOvertimeFromRaw("On")).isTrue();
		assertThat(LegacyPayrollOvertimeSettings.paysOvertimeFromRaw("0")).isFalse();
		assertThat(LegacyPayrollOvertimeSettings.paysOvertimeFromRaw("no")).isFalse();
		assertThat(LegacyPayrollOvertimeSettings.paysOvertimeFromRaw("")).isFalse();
	}
}
