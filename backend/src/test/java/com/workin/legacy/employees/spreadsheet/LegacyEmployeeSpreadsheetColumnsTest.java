package com.workin.legacy.employees.spreadsheet;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import tools.jackson.databind.ObjectMapper;

import com.workin.legacy.employees.spreadsheet.LegacyEmployeeSpreadsheetColumns.Column;

/**
 * The 28-column table and {@code employee_excel_normalize_header_key()}, held
 * against two independent statements of the truth.
 *
 * <p>{@link #theColumnTableIsExactlyLegacys()} freezes the table itself: every
 * key, required flag, group, Arabic and English label and alias list, in order,
 * written out a second time. Nothing about which spreadsheets this module
 * accepts can move without this test moving with it -- and because the vendored
 * JSON comes from PHP and {@code check_legacy_spreadsheet_columns_drift.py}
 * keeps it there, agreement here is agreement with legacy.
 *
 * <p>The normalization expectations come from {@code normalize_golden.json},
 * which is the output of the real {@code employee_excel_normalize_header_key()}
 * under a PHP 8.3 CLI. So the oracle for the folding rules is PHP itself --
 * including the English {@code Mobile attendance -> phone} collision, which is
 * asserted rather than fixed.
 */
class LegacyEmployeeSpreadsheetColumnsTest {

	/** {@code key|required|group_ar|group_en|label_ar|label_en|aliases}, in template order. */
	private static final List<String> EXPECTED_TABLE = List.of(
			"employee_code|required|||كود الموظف (اجباري)\nأرقام فقط|Employee code (required)\nDigits only|employee_code,emp_code,code,كود_الموظف,كود الموظف",
			"first_name|required|||الاسم الأول (اجباري)|First name (required)|first_name,firstname,fname,الاسم_الاول,الاسم الاول,الاسم الأول",
			"last_name|required|||الاسم الأخير (اجباري)|Last name (required)|last_name,lastname,lname,الاسم_الاخير,الاسم الاخير,الاسم الأخير",
			"country_code|optional|||كود الدولة (اختياري)|Country code (optional)|country_code,كود_الدولة,كود الدولة",
			"phone|optional|||رقم التليفون (اختياري)\nمطلوب لو الموظف هيسجل دخول من التطبيق|Phone (optional)\nRequired for employee app login|phone,mobile,الهاتف,رقم_الهاتف,رقم التلفون,رقم التليفون",
			"password|optional|||كلمة المرور (اختياري)\nمطلوبة مع رقم التليفون لتسجيل الدخول من التطبيق|Password (optional)\nRequired with phone for employee app login|password,كلمة_المرور,كلمه المرور,كلمة المرور",
			"shift_name|required|||الوردية (اجباري)\nأضف الورديات في النظام أولاً|Shift (required)\nAdd shifts in the system first|shift_name,shift,الوردية,اسم_الوردية,اسم الوردية",
			"national_id|optional|||الرقم القومي (اختياري)|National ID (optional)|national_id,nid,الرقم_القومي,الرقم القومي",
			"birth_date|optional|||تاريخ الميلاد (اختياري)|Birth date (optional)|birth_date,dob,تاريخ_الميلاد,تاريخ الميلاد",
			"gender|optional|||النوع (اختياري)|Gender (optional)|gender,النوع,الجنس",
			"address|optional|||العنوان (اختياري)|Address (optional)|address,العنوان",
			"is_mobile_attendance_enabled|required|||حضور من الموبايل (اجباري)\nنعم أو لا|Mobile attendance (required)\nYes or no|is_mobile_attendance_enabled,mobile_attendance,حضور_موبايل,حضور من الموبايل,السماح بتسجيل الحضور من الموبايل",
			"hire_date|optional|||تاريخ التعيين (اختياري)|Hire date (optional)|hire_date,تاريخ_التعيين,تاريخ التعيين",
			"branch_name|required|||الفرع (اجباري)\nأضف الفروع في النظام أولاً ثم اكتب اسم الفرع|Branch (required)\nAdd branches in the system first|branch_name,branch,الفرع,اسم_الفرع,اسم الفرع",
			"department_name|required|||القسم (اجباري)\nأضف الأقسام في النظام أولاً ثم اكتب اسم القسم|Department (required)\nAdd departments in the system first|department_name,department,القسم,اسم_القسم,اسم القسم",
			"job_title_name|required|||المسمى الوظيفي (اجباري)\nأضف المسميات في النظام أولاً ثم اكتب الاسم|Job title (required)\nAdd job titles in the system first|job_title_name,job_title,المسمى,المسمى_الوظيفي,المسمى الوظيفي",
			"expected_daily_hours|required|||ساعات العمل (اجباري)|Work hours (required)|expected_daily_hours,daily_hours,work_hours,ساعات_اليوم,ساعات العمل",
			"contract_duration_years|optional|||مدة العقد بالسنين (اختياري)|Contract years (optional)|contract_duration_years,contract_years,مدة_العقد,مدة العقد,مدة العقد بالسنين",
			"salary_basic|required|استحقاقات|Entitlements|الراتب الأساسي (اجباري)|Basic salary (required)|salary_basic,basic_salary,basic,الراتب_الاساسي,الراتب الأساسي",
			"salary_transport|optional|استحقاقات|Entitlements|بدل انتقال (اختياري)|Transport allowance (optional)|salary_transport,transport,بدل_انتقال,بدل انتقال",
			"salary_food_allowance|optional|استحقاقات|Entitlements|بدل طعام (اختياري)|Food allowance (optional)|salary_food_allowance,food_allowance,بدل_طعام,بدل طعام",
			"salary_risk_allowance|optional|استحقاقات|Entitlements|بدل مخاطر (اختياري)|Risk allowance (optional)|salary_risk_allowance,risk_allowance,بدل_مخاطر,بدل مخاطر",
			"salary_incentives|optional|استحقاقات|Entitlements|حوافز (اختياري)|Incentives (optional)|salary_incentives,incentives,حوافز",
			"salary_insurance_deduction|optional|استقطاعات|Deductions|التأمينات (اختياري)|Insurance (optional)|salary_insurance_deduction,insurance_deduction,التأمينات,خصم_تأمين,خصم تأمين",
			"salary_tax_deduction|optional|استقطاعات|Deductions|الضرائب (اختياري)|Taxes (optional)|salary_tax_deduction,tax_deduction,الضرائب,خصم_ضريبة,خصم ضريبة",
			"salary_advances_deduction|optional|استقطاعات|Deductions|تأمين طبي (اختياري)|Medical insurance (optional)|salary_advances_deduction,advances_deduction,تأمين_طبي,تأمين طبي,medical_insurance",
			"salary_fund_deduction|optional|استقطاعات|Deductions|الصناديق (اختياري)|Funds (optional)|salary_fund_deduction,fund_deduction,الصناديق,خصم_صندوق,خصم صندوق",
			"salary_penalty_deduction|optional|استقطاعات|Deductions|خصومات ثابتة أخرى (اختياري)|Other fixed deductions (optional)|salary_penalty_deduction,penalty_deduction,other_fixed_deductions,خصومات_ثابته_اخري,خصومات ثابتة أخرى");

	@Test
	void theColumnTableIsExactlyLegacys() {
		List<String> actual = new ArrayList<>();
		for (Column column : LegacyEmployeeSpreadsheetColumns.columns()) {
			actual.add(String.join("|",
					column.key(),
					column.required() ? "required" : "optional",
					column.groupAr() == null ? "" : column.groupAr(),
					column.groupEn() == null ? "" : column.groupEn(),
					column.labelAr(),
					column.labelEn(),
					String.join(",", column.aliases())));
		}
		// Element by element, so a diff names the column that moved.
		assertThat(actual).containsExactlyElementsOf(EXPECTED_TABLE);
		assertThat(actual).hasSize(28);
	}

	@Test
	void everyGeneratedArabicHeaderNormalizesToItsOwnColumn() {
		// The template the application hands out has to survive its own
		// analyzer -- all 28 headers, examples and second lines included.
		for (Map<String, Object> probe : golden("generated_arabic")) {
			String header = (String) probe.get("header");
			assertThat(LegacyEmployeeSpreadsheetColumns.normalizeHeaderKey(header))
					.as("header at index %s", probe.get("index"))
					.isEqualTo(probe.get("expected_key"))
					.isEqualTo(probe.get("normalized"));
		}
	}

	@Test
	void theEnglishLabelsNormalizeAsPhpDoesIncludingTheMobileAttendanceCollision() {
		int collisions = 0;
		for (Map<String, Object> probe : golden("label_en")) {
			String normalized = LegacyEmployeeSpreadsheetColumns.normalizeHeaderKey(
					(String) probe.get("label_en"));
			assertThat(normalized).as("label_en of %s", probe.get("key")).isEqualTo(probe.get("normalized"));
			if (!normalized.equals(probe.get("key"))) {
				collisions++;
				assertThat(probe.get("key")).isEqualTo("is_mobile_attendance_enabled");
				assertThat(normalized).isEqualTo("phone");
			}
		}
		// Exactly one English label lands on the wrong column, and it is that one.
		assertThat(collisions).isEqualTo(1);
	}

	@Test
	void theMobileAttendanceCollisionIsPreservedInEveryFormItArrivesIn() {
		// The alias prefix rule: "mobile_attendance" starts with the phone alias
		// "mobile" followed by an underscore, and phone comes first in the table.
		assertThat(LegacyEmployeeSpreadsheetColumns.normalizeHeaderKey("Mobile attendance")).isEqualTo("phone");
		assertThat(LegacyEmployeeSpreadsheetColumns.normalizeHeaderKey("mobile_attendance")).isEqualTo("phone");
		// The underscored key starts with "is_", so it reaches its own column.
		assertThat(LegacyEmployeeSpreadsheetColumns.normalizeHeaderKey("is_mobile_attendance_enabled"))
				.isEqualTo("is_mobile_attendance_enabled");
	}

	@Test
	void theFoldingRulesMatchPhpOnEveryProbedInput() {
		for (Map<String, Object> probe : golden("extra")) {
			assertThat(LegacyEmployeeSpreadsheetColumns.normalizeHeaderKey((String) probe.get("input")))
					.as("input %s", probe.get("input"))
					.isEqualTo(probe.get("normalized"));
		}
	}

	@Test
	void theTemplateHeadersAreTheOnesPhpGenerates() {
		@SuppressWarnings("unchecked")
		List<String> phpHeaders = (List<String>) goldenRoot().get("headers_raw");
		String generatedOn = (String) goldenRoot().get("generated_on");
		// The fixture was generated with empty lookups, so PHP fell back to its
		// own placeholders, and hire_date carries the date of that run.
		assertThat(LegacyEmployeeSpreadsheetColumns.templateHeaders(null, null, null, null, generatedOn))
				.containsExactlyElementsOf(phpHeaders);
	}

	@Test
	void theLookupExamplesReplaceThePlaceholdersOnlyWhenTheyAreNotBlank() {
		List<String> headers = LegacyEmployeeSpreadsheetColumns.templateHeaders(
				"  Night  ", "", "   ", null, "2026-08-21");
		assertThat(headers.get(6)).endsWith("Night");
		assertThat(headers.get(12)).endsWith("2026-08-21");
		// Blank, whitespace-only and null lookups all fall back.
		assertThat(headers.get(13)).endsWith("الفرع الرئيسي");
		assertThat(headers.get(14)).endsWith("الموارد البشرية");
		assertThat(headers.get(15)).endsWith("موظف");
	}

	@Test
	void theGroupRowMergesAndStylesAreTheOnesPhpBuilds() {
		@SuppressWarnings("unchecked")
		List<String> phpGroupRow = (List<String>) goldenRoot().get("group_row");
		@SuppressWarnings("unchecked")
		List<String> phpMerges = (List<String>) goldenRoot().get("merges");

		assertThat(LegacyEmployeeSpreadsheetColumns.templateGroupRow()).containsExactlyElementsOf(phpGroupRow);
		assertThat(LegacyEmployeeSpreadsheetColumns.templateGroupMerges()).containsExactlyElementsOf(phpMerges);

		// The CSV branch builds a different row: the label repeats across every
		// column of its block, because a CSV has no merges to carry it.
		List<String> csvGroupRow = LegacyEmployeeSpreadsheetColumns.csvGroupRow();
		assertThat(csvGroupRow).hasSize(28);
		assertThat(csvGroupRow.subList(18, 23)).containsOnly("استحقاقات");
		assertThat(csvGroupRow.subList(23, 28)).containsOnly("استقطاعات");
		assertThat(csvGroupRow.subList(0, 18)).containsOnly("");

		Map<Integer, Map<Integer, Integer>> styles = LegacyEmployeeSpreadsheetColumns.templateCellStyles();
		assertThat(styles.keySet()).containsExactlyInAnyOrder(0, 1);
		for (int row : List.of(0, 1)) {
			assertThat(styles.get(row)).containsOnlyKeys(18, 19, 20, 21, 22, 23, 24, 25, 26, 27);
			assertThat(styles.get(row).get(18)).isEqualTo(4);
			assertThat(styles.get(row).get(23)).isEqualTo(5);
		}
	}

	@Test
	void theSalaryGroupRowIsRecognisedExactlyAsPhpRecognisesIt() {
		for (Map<String, Object> probe : golden("is_group_row")) {
			@SuppressWarnings("unchecked")
			List<String> cells = (List<String>) probe.get("cells");
			assertThat(LegacyEmployeeSpreadsheetColumns.isSalaryGroupRow(cells))
					.as("cells %s", cells)
					.isEqualTo(probe.get("result"));
		}
		// A null cell is PHP's missing cell: (string) null is the empty string.
		List<String> withNulls = new ArrayList<>();
		withNulls.add("استحقاقات");
		withNulls.add(null);
		assertThat(LegacyEmployeeSpreadsheetColumns.isSalaryGroupRow(withNulls)).isTrue();
		// The template's own group row, in both of the shapes it is written in.
		assertThat(LegacyEmployeeSpreadsheetColumns.isSalaryGroupRow(
				LegacyEmployeeSpreadsheetColumns.templateGroupRow())).isTrue();
		assertThat(LegacyEmployeeSpreadsheetColumns.isSalaryGroupRow(
				LegacyEmployeeSpreadsheetColumns.csvGroupRow())).isTrue();
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> golden(String section) {
		return (List<Map<String, Object>>) goldenRoot().get(section);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> goldenRoot() {
		try (InputStream stream = new ClassPathResource("legacy/spreadsheet/normalize_golden.json")
				.getInputStream()) {
			return new ObjectMapper().readValue(stream, Map.class);
		} catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
