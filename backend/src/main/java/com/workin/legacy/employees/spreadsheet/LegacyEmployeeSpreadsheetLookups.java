package com.workin.legacy.employees.spreadsheet;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code employee_excel_build_lookups()}'s four maps: branch, department, job
 * title and shift names against their ids, keyed by
 * {@code mb_strtolower(trim($name))}.
 *
 * <p>The template's example values are {@code array_key_first()} of each map --
 * the <em>key</em>, so the example a user sees is the lower-cased, trimmed form
 * of the name, not the name as it is stored. That is legacy's behaviour and it
 * is visible in the file the endpoint hands out, so it is reproduced rather
 * than tidied.
 *
 * <p>None of the four queries carries an {@code ORDER BY}, so which name is
 * "first" is the order the server happens to return. J2 remains unresolved on
 * the related duplicate-name question, and nothing here invents an ordering to
 * make it look settled.
 */
public record LegacyEmployeeSpreadsheetLookups(
		LinkedHashMap<String, Long> branches,
		LinkedHashMap<String, Long> departments,
		LinkedHashMap<String, Long> jobTitles,
		LinkedHashMap<String, Long> shifts) {

	/** {@code $lookups['branches'] !== [] ? (string) array_key_first(...) : ''}. */
	public String firstBranchName() {
		return firstKey(branches);
	}

	public String firstDepartmentName() {
		return firstKey(departments);
	}

	public String firstJobTitleName() {
		return firstKey(jobTitles);
	}

	public String firstShiftName() {
		return firstKey(shifts);
	}

	private static String firstKey(Map<String, Long> lookup) {
		return lookup.isEmpty() ? "" : lookup.keySet().iterator().next();
	}

	/**
	 * {@code array_keys($lookups[...])} -- the shape {@code analyze_excel.php}
	 * echoes back so the client can offer the valid choices.
	 */
	public List<String> branchNames() {
		return List.copyOf(branches.keySet());
	}

	public List<String> departmentNames() {
		return List.copyOf(departments.keySet());
	}

	public List<String> jobTitleNames() {
		return List.copyOf(jobTitles.keySet());
	}

	public List<String> shiftNames() {
		return List.copyOf(shifts.keySet());
	}

}
