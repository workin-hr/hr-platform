package com.workin.backend.platformadmin.hr;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** {@code payroll/page.php}'s {@code $fmtPay} and its one-decimal hours, against PHP 8.3's output. */
class PayrollDisplayTest {

	@Test
	void moneyIsWholePoundsGroupedInThrees() {
		assertThat(PayrollDisplay.money(new BigDecimal("5180.00"))).isEqualTo("5,180");
		assertThat(PayrollDisplay.money(new BigDecimal("1234.50"))).as("half rounds away from zero").isEqualTo("1,235");
		assertThat(PayrollDisplay.money(new BigDecimal("9999.90"))).isEqualTo("10,000");
		assertThat(PayrollDisplay.money(770)).as("an integer column").isEqualTo("770");
		assertThat(PayrollDisplay.money("5180.00")).as("a numeric string, through (float)").isEqualTo("5,180");
	}

	@Test
	void missingMoneyIsLegacysZeroNotADash() {
		assertThat(PayrollDisplay.money(null)).isEqualTo("0");
	}

	@Test
	void overtimeHoursAlwaysShowOnePlace() {
		assertThat(PayrollDisplay.oneDecimal(new BigDecimal("5.0"))).isEqualTo("5.0");
		assertThat(PayrollDisplay.oneDecimal(5)).isEqualTo("5.0");
		assertThat(PayrollDisplay.oneDecimal("5")).as("a numeric string").isEqualTo("5.0");
		assertThat(PayrollDisplay.oneDecimal(new BigDecimal("2.25"))).isEqualTo("2.3");
		assertThat(PayrollDisplay.oneDecimal(new BigDecimal("1234.5"))).isEqualTo("1,234.5");
		assertThat(PayrollDisplay.oneDecimal(null)).isEqualTo("0.0");
	}

}
