package com.workin.legacy.employees.spreadsheet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.workin.legacy.LegacyValues;
import com.workin.legacy.phone.CanonicalPhone;
import com.workin.legacy.phone.CanonicalPhones;
import com.workin.legacy.phone.LegacyPhoneCountries;
import com.workin.legacy.phone.LegacyPhoneNumbers;
import com.workin.legacy.phone.PhoneLookup;

/**
 * What one bulk-update sheet knows about the database, read in sets rather
 * than per row (D-294).
 *
 * <p>{@code employee_excel_row_to_update_payload()} asks the database up to
 * five questions per row -- shift, department-in-branch, title-in-department,
 * the phone countries, the global phone check -- and the apply step asks
 * more. Each is answered here: the company's employees and the phone
 * countries once per sheet, and the rest from sets re-read for each chunk
 * just before it is validated
 * ({@link LegacyEmployeeUpdateAnalyzer#refresh}), so validating a row costs
 * no statement and what a chunk sees is at most one chunk old.
 *
 * <h2>The one answer a sheet changes as it applies</h2>
 * <p>Rows are applied in order, and PHP validates each row after the rows
 * before it were written. Of everything read here only one thing is written
 * by an earlier row and read by a later one: <b>who holds a phone number</b>.
 * Codes repeated in a file are refused before this is consulted, and the
 * org tables are never written. So the phone holders are the one mutable
 * part, updated by {@link #claimPhone} when a row applies and restored by
 * {@link #rollBackTo} when a write the claim assumed does not happen.
 */
final class LegacyEmployeeUpdateSheet {

	private final long companyId;

	private final LegacyEmployeeSpreadsheetLookups lookups;

	private final Map<String, Map<String, Object>> employeesByCode;

	private final LegacyPhoneCountries phoneCountries;

	private final LegacyPhoneNumbers phoneNumbers;

	/** The current chunk's references, replaced by {@link #replaceReferences} for each chunk. */
	private Set<Long> shiftsInCompany = Set.of();

	private Set<LegacyEmployeeUpdateSheetStore.DepartmentBranch> departmentBranches = Set.of();

	private Set<Long> activeDepartmentsInCompany = Set.of();

	private Map<Long, Long> activeJobTitleDepartments = Map.of();

	/** Holder rows by the E.164 number their own {@code (phone, country_code)} is. */
	private final Map<String, Map<Long, Map<String, Object>>> holdersByNumber = new HashMap<>();

	/** Which number each holder is filed under, so a claim can move it. */
	private final Map<Long, String> numberOfHolder = new HashMap<>();

	/** Undo entries for {@link #claimPhone}, newest first. */
	private final Deque<Runnable> undo = new ArrayDeque<>();

	LegacyEmployeeUpdateSheet(long companyId, LegacyEmployeeSpreadsheetLookups lookups,
			Map<String, Map<String, Object>> employeesByCode, LegacyPhoneCountries phoneCountries,
			LegacyPhoneNumbers phoneNumbers) {
		this.companyId = companyId;
		this.lookups = lookups;
		this.employeesByCode = employeesByCode;
		this.phoneCountries = phoneCountries;
		this.phoneNumbers = phoneNumbers;
	}

	/** The next chunk's shifts, department-branch links, active departments and job titles, as just read. */
	void replaceReferences(Set<Long> shifts, Set<LegacyEmployeeUpdateSheetStore.DepartmentBranch> links,
			Set<Long> activeDepartments, Map<Long, Long> activeJobTitles) {
		this.shiftsInCompany = shifts;
		this.departmentBranches = links;
		this.activeDepartmentsInCompany = activeDepartments;
		this.activeJobTitleDepartments = activeJobTitles;
	}

	long companyId() {
		return this.companyId;
	}

	LegacyEmployeeSpreadsheetLookups lookups() {
		return this.lookups;
	}

	/** The company's employee for a normalized code, as {@code employee_excel_employees_by_code()} read it. */
	Map<String, Object> employee(String code) {
		return this.employeesByCode.get(code);
	}

	LegacyPhoneCountries phoneCountries() {
		return this.phoneCountries;
	}

	LegacyPhoneNumbers phoneNumbers() {
		return this.phoneNumbers;
	}

	/** {@code shift_belongs_to_company()}. */
	boolean shiftBelongsToCompany(long shiftId) {
		return this.shiftsInCompany.contains(shiftId);
	}

	/** {@link LegacyEmployeeSpreadsheetAnalyzer#departmentValidForBranch}, answered from the sets. */
	boolean departmentValidForBranch(Long departmentId, Long branchId) {
		if (departmentId == null || departmentId <= 0) {
			return true;
		}
		if (branchId != null && branchId > 0) {
			return this.departmentBranches.contains(
					new LegacyEmployeeUpdateSheetStore.DepartmentBranch(departmentId, branchId));
		}
		return this.activeDepartmentsInCompany.contains(departmentId);
	}

	/** {@code job_title_belongs_to_department()}. */
	boolean jobTitleBelongsToDepartment(long jobTitleId, long departmentId) {
		Long department = this.activeJobTitleDepartments.get(jobTitleId);
		return department != null && department == departmentId;
	}

	/**
	 * Who holds each of {@code numbers} now: {@code holders} as
	 * {@link LegacyEmployeeUpdateSheetStore#phoneHolders} just read them,
	 * replacing whatever was filed under those numbers. Earlier chunks'
	 * claims are committed by then, so the read already reflects them; and
	 * every claim before this point is final, so none is undone past it.
	 */
	void replacePhoneHolders(List<CanonicalPhone> numbers, List<Map<String, Object>> holders) {
		for (CanonicalPhone number : numbers) {
			Map<Long, Map<String, Object>> filed = this.holdersByNumber.remove(number.e164());
			if (filed != null) {
				filed.keySet().forEach(this.numberOfHolder::remove);
			}
		}
		for (Map<String, Object> holder : holders) {
			file(((Number) holder.get("id")).longValue(), holder);
		}
		this.undo.clear();
	}

	/**
	 * {@code employee_phone_exists_globally($phone, $exclude)} against the
	 * holders as this sheet has left them: another row that is this number,
	 * verified by {@link PhoneLookup} exactly as the single-number query's
	 * candidates are.
	 */
	boolean phoneExistsGlobally(CanonicalPhone phone, long excludeEmployeeId) {
		Map<Long, Map<String, Object>> filed = this.holdersByNumber.get(phone.e164());
		if (filed == null || filed.isEmpty()) {
			return false;
		}
		List<Map<String, Object>> others = new ArrayList<>(filed.size());
		filed.forEach((id, row) -> {
			if (id != excludeEmployeeId) {
				others.add(row);
			}
		});
		return !PhoneLookup.of(phone).verified(others).isEmpty();
	}

	/** A position {@link #rollBackTo} can return the holders to. */
	int mark() {
		return this.undo.size();
	}

	/**
	 * Records that {@code employee} now stores {@code phone} under
	 * {@code countryCode}, as its applied row wrote. A rejected join request
	 * still never counts, as the query never returns one.
	 */
	void claimPhone(Map<String, Object> employee, Object phone, Object countryCode) {
		long id = ((Number) employee.get("id")).longValue();
		String previousNumber = this.numberOfHolder.get(id);
		Map<String, Object> previousRow = previousNumber == null ? null
				: this.holdersByNumber.get(previousNumber).get(id);
		unfile(id);
		if (!"rejected".equals(LegacyValues.toPhpString(employee.get("join_request_status")))) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", id);
			row.put("phone", phone);
			row.put("country_code", countryCode);
			file(id, row);
		}
		this.undo.push(() -> {
			unfile(id);
			if (previousRow != null) {
				file(id, previousRow);
			}
		});
	}

	/** Undoes every {@link #claimPhone} made since {@code mark}. */
	void rollBackTo(int mark) {
		while (this.undo.size() > mark) {
			this.undo.pop().run();
		}
	}

	private void file(long id, Map<String, Object> row) {
		unfile(id);
		Object country = row.get("country_code");
		Optional<CanonicalPhone> number = CanonicalPhones.parse(row.get("phone"),
				CanonicalPhones.countryCodeAsRead(country));
		if (number.isEmpty()) {
			// Not a number, so no lookup can verify it: the single-number
			// query would return it as a candidate and then discard it.
			return;
		}
		this.holdersByNumber.computeIfAbsent(number.get().e164(), key -> new LinkedHashMap<>()).put(id, row);
		this.numberOfHolder.put(id, number.get().e164());
	}

	private void unfile(long id) {
		String number = this.numberOfHolder.remove(id);
		if (number != null) {
			this.holdersByNumber.get(number).remove(id);
		}
	}
}
