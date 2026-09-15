package com.workin.backend.platformadmin.hr;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link EmployeePicker}'s labels, the text every picker page searches and shows.
 *
 * <p>Unit-tested because the cases are combinations of three strings; the pages'
 * end-to-end tests check that the labels reach the page.
 */
class EmployeePickerTest {

	@Test
	void aNamedEmployeeShowsNameThenCodeThenCompany() {
		assertThat(EmployeePicker.label("Aya Alpha", "A100", null)).isEqualTo("Aya Alpha (A100)");
		assertThat(EmployeePicker.label("  Aya Alpha ", " A100 ", "Alpha Co")).isEqualTo("Aya Alpha (A100) — Alpha Co");
		assertThat(EmployeePicker.label("Aya Alpha", "", "Alpha Co")).isEqualTo("Aya Alpha — Alpha Co");
	}

	@Test
	void anEmployeeWithNoNameIsShownByCodeAlone() {
		// Not " (A100)": a blank name must not leave a leading space and a
		// bracketed code as the only thing the list shows.
		assertThat(EmployeePicker.label(null, "A100", null)).isEqualTo("A100");
		assertThat(EmployeePicker.label("", "A100", null)).isEqualTo("A100");
		assertThat(EmployeePicker.label("   ", "A100", "Alpha Co")).isEqualTo("A100 — Alpha Co");
	}
}
