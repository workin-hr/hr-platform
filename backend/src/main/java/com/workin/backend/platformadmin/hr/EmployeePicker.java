package com.workin.backend.platformadmin.hr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

/**
 * What the admin pages' employee picker ({@code employeePicker.jte},
 * {@code emp-picker.js}) is given.
 *
 * <p>Labels follow legacy's {@code hr_employee_option_label}: the name, the code
 * in brackets when there is one, then " — " and the company when the list spans
 * companies, which is when an option carries one. An employee with no name is
 * shown by the code alone, where legacy prints a leading space and " (CODE)".
 */
public final class EmployeePicker {

	private static final ObjectMapper JSON = new ObjectMapper();

	private EmployeePicker() {
	}

	/** The list the picker searches, as the JSON a page renders once. */
	public static String json(List<LeaveBalance.EmployeeOption> options) {
		List<Map<String, Object>> entries = new ArrayList<>(options.size());
		for (LeaveBalance.EmployeeOption option : options) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("id", option.id());
			entry.put("label", label(option.name(), option.code(), option.companyName()));
			entry.put("code", trimmed(option.code()));
			entry.put("name", trimmed(option.name()));
			entries.add(entry);
		}
		return JSON.writeValueAsString(entries);
	}

	/**
	 * One employee's label. A row passes no company: legacy labels a row's own
	 * employee without one ({@code hr_advances_row_actions}).
	 */
	public static String label(String name, String code, String companyName) {
		String shownName = trimmed(name);
		String shownCode = trimmed(code);
		StringBuilder label = new StringBuilder(shownName.isEmpty() ? shownCode
				: shownCode.isEmpty() ? shownName : shownName + " (" + shownCode + ")");
		if (companyName != null && !companyName.isEmpty()) {
			label.append(" — ").append(companyName);
		}
		return label.toString();
	}

	private static String trimmed(String value) {
		return value == null ? "" : value.trim();
	}
}
