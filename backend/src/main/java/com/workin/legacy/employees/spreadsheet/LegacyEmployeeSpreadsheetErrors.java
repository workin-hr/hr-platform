package com.workin.legacy.employees.spreadsheet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.workin.legacy.LegacyValues;

/**
 * {@code employee_excel_error_message()},
 * {@code employee_excel_error_field_key()} and the two functions that map a
 * row's error codes through them.
 *
 * <p>These strings are Arabic regardless of request locale -- they are written
 * into the helper rather than resolved through {@code t()}, so unlike the
 * envelope's {@code message} they do not follow {@code ?lang=}. Reproduced as
 * they are: the client renders them directly.
 *
 * <p>An unmapped code is returned as itself, which is how a code the helper
 * does not know still reaches the client intact.
 */
public final class LegacyEmployeeSpreadsheetErrors {

	private LegacyEmployeeSpreadsheetErrors() {
	}

	/**
	 * The {@code $ctx} both mapping functions build: the row's code, phone and
	 * shift name, used to interpolate the offending value into a message.
	 *
	 * <p>The code is run through {@code normalize_employee_code()} first, so a
	 * message quotes the collapsed form rather than the raw cell.
	 */
	public record Context(String employeeCode, String phone, String shiftName) {

		public static Context of(Map<String, Object> row) {
			return new Context(
					normalizeEmployeeCode(LegacyEmployeeSpreadsheetValues.asString(row.get("employee_code"))),
					LegacyValues.phpTrim(LegacyEmployeeSpreadsheetValues.asString(row.get("phone"))),
					LegacyValues.phpTrim(LegacyEmployeeSpreadsheetValues.asString(row.get("shift_name"))));
		}

	}

	/**
	 * {@code normalize_employee_code()} ({@code functions.php:45}): trim, then
	 * collapse every internal whitespace run to one space.
	 */
	public static String normalizeEmployeeCode(String raw) {
		return LegacyValues.phpTrim(raw == null ? "" : raw).replaceAll("\\s+", " ");
	}

	/** {@code employee_excel_error_messages()}: one message per code, in order. */
	public static List<String> messages(List<String> codes, Map<String, Object> row) {
		Context context = Context.of(row);
		List<String> messages = new ArrayList<>(codes.size());
		for (String code : codes) {
			messages.add(message(code, context));
		}
		return messages;
	}

	/**
	 * {@code employee_excel_field_errors()}: the codes grouped by the field they
	 * belong to, in first-seen order, with a second message for the same field
	 * appended after a newline. Codes that map to no field are dropped.
	 */
	public static Map<String, String> fieldErrors(List<String> codes, Map<String, Object> row) {
		Context context = Context.of(row);
		Map<String, String> fieldErrors = new LinkedHashMap<>();
		for (String code : codes) {
			String field = fieldKey(code);
			if (field == null) {
				continue;
			}
			String message = message(code, context);
			fieldErrors.merge(field, message, (existing, added) -> existing + "\n" + added);
		}
		return fieldErrors;
	}

	/** {@code employee_excel_error_field_key()}. */
	public static String fieldKey(String code) {
		return switch (code) {
			case "first_name_required" -> "first_name";
			case "last_name_required" -> "last_name";
			case "employee_code_required", "employee_code_invalid", "employee_code_exists",
					"employee_code_already_exists", "employee_code_duplicate_in_file" -> "employee_code";
			case "phone_already_exists", "phone_exists", "invalid_phone", "invalid_phone_number" -> "phone";
			case "expected_daily_hours_required" -> "expected_daily_hours";
			case "shift_required", "shift_not_found" -> "shift_name";
			case "branch_required", "branch_not_found" -> "branch_name";
			case "department_required", "department_not_found", "department_branch_mismatch"
					-> "department_name";
			case "job_title_required", "job_title_not_found", "job_title_department_mismatch"
					-> "job_title_name";
			case "mobile_attendance_required", "mobile_attendance_invalid"
					-> "is_mobile_attendance_enabled";
			case "salary_basic_required" -> "salary_basic";
			default -> null;
		};
	}

	/** {@code employee_excel_error_message()}, with its {@code default => $code} arm. */
	public static String message(String code, Context context) {
		String employeeCode = LegacyValues.phpTrim(context.employeeCode());
		String phone = LegacyValues.phpTrim(context.phone());
		String shift = LegacyValues.phpTrim(context.shiftName());

		return switch (code) {
			case "first_name_required" -> "الاسم الأول مطلوب";
			case "last_name_required" -> "الاسم الأخير مطلوب";
			case "employee_code_required" -> "كود الموظف مطلوب";
			case "employee_code_invalid" -> employeeCode.isEmpty()
					? "كود الموظف يجب أن يكون أرقاماً فقط"
					: "كود الموظف (" + employeeCode + ") يجب أن يكون أرقاماً فقط";
			case "employee_code_exists", "employee_code_already_exists" -> employeeCode.isEmpty()
					? "كود الموظف مستخدم مسبقاً في الشركة"
					: "كود الموظف (" + employeeCode + ") مستخدم مسبقاً في الشركة — غيّره لكود غير مستخدم";
			case "employee_code_duplicate_in_file" -> employeeCode.isEmpty()
					? "كود الموظف مكرر داخل الملف"
					: "كود الموظف (" + employeeCode + ") مكرر داخل الملف";
			case "phone_already_exists", "phone_exists" -> phone.isEmpty()
					? "رقم التليفون مستخدم مسبقاً"
					: "رقم التليفون (" + phone + ") مستخدم مسبقاً";
			case "expected_daily_hours_required" -> "ساعات العمل مطلوبة";
			case "shift_required" -> "الوردية مطلوبة";
			case "shift_not_found" -> shift.isEmpty()
					? "الوردية غير موجودة — اكتب اسم وردية مضافتة في الشركة"
					: "الوردية (" + shift + ") غير موجودة — اكتب اسم وردية مضافتة في الشركة";
			case "branch_required" -> "الفرع مطلوب — أضف الفروع في النظام أولاً ثم اكتب اسم الفرع";
			case "branch_not_found" -> "الفرع غير موجود — أضف الفرع في النظام أولاً أو اكتب اسم فرع موجود";
			case "department_required" -> "القسم مطلوب — أضف الأقسام في النظام أولاً ثم اكتب اسم القسم";
			case "department_not_found" -> "القسم غير موجود — أضف القسم في النظام أولاً أو اكتب اسم قسم موجود";
			case "department_branch_mismatch" -> "القسم لا ينتمي للفرع المحدد";
			case "job_title_required" -> "المسمى الوظيفي مطلوب — أضف المسميات في النظام أولاً ثم اكتب الاسم";
			case "job_title_not_found" ->
					"المسمى الوظيفي غير موجود — أضف المسمى في النظام أولاً أو اكتب اسم مسمى موجود";
			case "job_title_department_mismatch" -> "المسمى الوظيفي لا ينتمي للقسم المحدد";
			case "mobile_attendance_required" -> "حضور من الموبايل مطلوب — اكتب نعم أو لا";
			case "mobile_attendance_invalid" -> "حضور من الموبايل غير صالح — اكتب نعم أو لا";
			case "invalid_phone" -> phone.isEmpty()
					? "رقم التلفون غير صالح لهذه الدولة (مثال: 010… أو 10…)"
					: "رقم التليفون (" + phone + ") غير صالح لهذه الدولة (مثال: 010… أو 10…)";
			case "invalid_phone_number" -> phone.isEmpty()
					? "رقم التلفون غير صالح لهذه الدولة"
					: "رقم التليفون (" + phone + ") غير صالح لهذه الدولة";
			case "salary_basic_required" -> "الراتب الأساسي مطلوب";
			case "employee_create_failed" -> "تعذّر إنشاء الموظف. راجع البيانات وحاول مرة أخرى";
			default -> code;
		};
	}

}
