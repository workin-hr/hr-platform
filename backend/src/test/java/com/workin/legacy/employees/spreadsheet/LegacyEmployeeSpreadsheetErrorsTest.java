package com.workin.legacy.employees.spreadsheet;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@code employee_excel_error_message()} and
 * {@code employee_excel_error_field_key()}.
 *
 * <p>Both fall through to a {@code default} arm -- the code itself for the
 * message, {@code null} for the field key -- so a code with no arm does not
 * fail anywhere. It ships. hr-legacy {@code 505004f} added four codes for the
 * bulk-update path and this port added none of them, while the update analyzer
 * and bulk updater produced all four: an Arabic-speaking HR user was shown the
 * bare token {@code nothing_to_update}, and the per-cell highlight never fired
 * because the field key was null.
 *
 * <p>The two end-to-end tests for those endpoints assert {@code errors} but
 * never {@code error_messages} or {@code field_errors}, which is why the suite
 * was green. These assert the rendered text.
 */
class LegacyEmployeeSpreadsheetErrorsTest {

	private static final LegacyEmployeeSpreadsheetErrors.Context NO_CONTEXT =
			new LegacyEmployeeSpreadsheetErrors.Context("", "", "");

	@Test
	void theFourUpdatePathCodesRenderTheirArabicMessage() {
		assertThat(LegacyEmployeeSpreadsheetErrors.message("nothing_to_update", NO_CONTEXT))
				.isEqualTo("لا توجد قيم للتعديل — املأ عموداً واحداً على الأقل غير كود الموظف");
		assertThat(LegacyEmployeeSpreadsheetErrors.message("employee_update_failed", NO_CONTEXT))
				.isEqualTo("تعذّر تعديل الموظف. راجع البيانات وحاول مرة أخرى");
		assertThat(LegacyEmployeeSpreadsheetErrors.message("gender_invalid", NO_CONTEXT))
				.isEqualTo("النوع غير صالح — اكتب ذكر أو أنثى");
	}

	/** {@code employee_not_found} interpolates the code, and omits the parentheses when it is blank. */
	@Test
	void employeeNotFoundNamesTheCodeItLookedFor() {
		assertThat(LegacyEmployeeSpreadsheetErrors.message("employee_not_found",
				new LegacyEmployeeSpreadsheetErrors.Context("999999", "", "")))
				.isEqualTo("كود الموظف (999999) غير موجود في الشركة");
		assertThat(LegacyEmployeeSpreadsheetErrors.message("employee_not_found", NO_CONTEXT))
				.isEqualTo("كود الموظف غير موجود في الشركة");
	}

	/** Without these the row is flagged but no cell is highlighted. */
	@Test
	void theTwoNewCodesResolveToTheColumnTheyBelongTo() {
		assertThat(LegacyEmployeeSpreadsheetErrors.fieldKey("employee_not_found")).isEqualTo("employee_code");
		assertThat(LegacyEmployeeSpreadsheetErrors.fieldKey("gender_invalid")).isEqualTo("gender");
	}

	/**
	 * The fall-through is deliberate in PHP, so it is pinned rather than
	 * removed -- but it must only be reachable for a code neither side knows.
	 */
	@Test
	void anUnknownCodeStillFallsThroughToItself() {
		assertThat(LegacyEmployeeSpreadsheetErrors.message("something_new", NO_CONTEXT))
				.isEqualTo("something_new");
		assertThat(LegacyEmployeeSpreadsheetErrors.fieldKey("something_new")).isNull();
	}
}
