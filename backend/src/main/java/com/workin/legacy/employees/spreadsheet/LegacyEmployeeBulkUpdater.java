package com.workin.legacy.employees.spreadsheet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyPhpArray;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.workin.legacy.employees.LegacyEmployeeStore;

/**
 * {@code employee_excel_update_rows()} and {@code employee_excel_apply_update()}
 * -- applying reviewed rows from the bulk-update sheet.
 *
 * <h2>One transaction per row, not per batch</h2>
 * <p>PHP opens and commits a transaction inside {@code apply_update()}, once
 * per row, and a row that throws rolls back only itself before the loop
 * continues. A partially successful batch is therefore the <b>normal</b>
 * outcome, reported as {@code updated} plus a {@code failed} list, not an
 * error. Wrapping the batch in one transaction would be a better-behaved API
 * and a different one: a single bad row would silently discard every good row
 * the operator had already reviewed, and the desktop client shows the failed
 * list expecting the rest to have landed.
 *
 * <p>What each row's transaction does cover is the three writes that belong
 * together -- the employee update, the shift assignment and the salary patch.
 * A row whose salary write fails leaves no half-applied employee.
 */
@Component
public class LegacyEmployeeBulkUpdater {

	/**
	 * {@code $allowed}: the columns a sheet may write, in PHP's order. The
	 * order matters only for reproducing the generated SQL, but the
	 * <em>membership</em> is the control -- a payload key absent from this
	 * list is silently not written, which is how {@code employee_code} and
	 * {@code id} stay identifiers rather than becoming editable.
	 */
	private static final List<String> ALLOWED_COLUMNS = List.of(
			"first_name", "last_name", "phone", "country_code", "branch_id",
			"department_id", "job_title_id", "national_id", "birth_date", "gender",
			"address", "hire_date", "contract_duration_months", "expected_daily_hours",
			"is_mobile_attendance_enabled");

	/** Salary payload key to {@code salary_contracts} column, in PHP's order. */
	private static final Map<String, String> SALARY_COLUMNS = salaryColumns();

	private final LegacyEmployeeStore store;

	private final LegacyEmployeeUpdateAnalyzer analyzer;

	private final LegacyClock clock;

	private final JdbcTemplate jdbcTemplate;

	private final TransactionTemplate transactionTemplate;

	/** {@code password_hash($plain, PASSWORD_BCRYPT)} -- the same encoder the create sheet uses. */
	private final PasswordEncoder bcrypt;

	public LegacyEmployeeBulkUpdater(
			LegacyEmployeeStore store, LegacyEmployeeUpdateAnalyzer analyzer,
			LegacyClock clock, DataSource legacyDataSource, PasswordEncoder bcrypt) {
		this.store = store;
		this.analyzer = analyzer;
		this.clock = clock;
		this.bcrypt = bcrypt;
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
		// Its own transaction manager over the same DataSource: each row's
		// writes commit independently, which an ambient request transaction
		// would defeat by folding them all into one.
		this.transactionTemplate =
				new TransactionTemplate(new DataSourceTransactionManager(legacyDataSource));
	}

	/**
	 * {@code employee_excel_update_rows()}.
	 *
	 * @return {@code updated}, {@code failed} and {@code updated_ids}, the
	 *         three keys the endpoint returns unchanged
	 */
	public Map<String, Object> updateRows(long companyId, LegacyPhpArray rows,
			LegacyEmployeeSpreadsheetLookups lookups) {

		Map<String, Map<String, Object>> employeesByCode = this.store.employeesByCode(companyId);
		Set<String> seenCodes = new HashSet<>();
		List<Map<String, Object>> failed = new ArrayList<>();
		List<Object> updatedIds = new ArrayList<>();
		long updated = 0;

		// Iterated as a PHP array, not a Java list: row_index is $index + 1 over
		// the *submitted keys*, so a JSON object or a sparse array numbers its
		// rows the way PHP would rather than by position.
		for (LegacyPhpArray.Entry entry : rows.entries()) {
			Map<String, Object> row = rowOf(entry.value());
			String code = LegacyEmployeeSpreadsheetErrors.normalizeEmployeeCode(
					row.get("employee_code") == null ? "" : String.valueOf(row.get("employee_code")));

			// Checked before parsing, as PHP does: a second row for the same
			// code fails on the duplicate alone, whatever else is wrong with it.
			if (!code.isEmpty() && !seenCodes.add(code)) {
				failed.add(failure(entry, List.of("employee_code_duplicate_in_file"), row));
				continue;
			}

			LegacyEmployeeUpdateAnalyzer.Parsed parsed =
					this.analyzer.rowToUpdatePayload(row, companyId, lookups, employeesByCode);
			if (!parsed.errors().isEmpty()) {
				failed.add(failure(entry, parsed.errors(), row));
				continue;
			}

			List<String> applyErrors = applyUpdate(companyId, parsed.payload());
			if (!applyErrors.isEmpty()) {
				failed.add(failure(entry, applyErrors, row));
				continue;
			}

			updated++;
			Object employeeId = parsed.payload().get("id");
			updatedIds.add(employeeId);
			// Re-read and re-cache, as PHP does. Duplicates are already
			// rejected above, so nothing in this batch reads it back -- it is
			// reproduced because dropping a write would be a silent divergence,
			// not because a caller depends on it.
			Map<String, Object> fresh = this.store.findOne(asLong(employeeId), companyId);
			if (fresh != null && !code.isEmpty()) {
				employeesByCode.put(code, fresh);
			}
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("updated", updated);
		result.put("failed", failed);
		result.put("updated_ids", updatedIds);
		return result;
	}

	/** One failed row, in PHP's key order -- {@code data} is the row as submitted. */
	private static Map<String, Object> failure(
			LegacyPhpArray.Entry entry, List<String> errors, Map<String, Object> row) {
		Map<String, Object> failure = new LinkedHashMap<>();
		failure.put("row_index", entry.indexPlusOne());
		failure.put("errors", errors);
		failure.put("error_messages", LegacyEmployeeSpreadsheetErrors.messages(errors, row));
		// The row as submitted, not the coerced map: PHP puts $row straight in.
		failure.put("data", entry.value());
		return failure;
	}

	/** {@code $row} coerced to a string-keyed map, as the importer does. */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> rowOf(Object value) {
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> row = new LinkedHashMap<>();
			((Map<Object, Object>) map).forEach((key, item) -> row.put(String.valueOf(key), item));
			return row;
		}
		return new LinkedHashMap<>();
	}

	/**
	 * {@code employee_excel_apply_update()}.
	 *
	 * @return the errors, empty when the row applied
	 */
	private List<String> applyUpdate(long companyId, Map<String, Object> payload) {
		long id = asLong(payload.get("id"));
		if (id < 1) {
			return List.of("employee_not_found");
		}
		Map<String, Object> employee = this.store.findOne(id, companyId);
		if (employee == null) {
			// Re-read under the company predicate even though the analyzer
			// already matched the code within this company: the payload
			// travels through the client between analyze and update, so this
			// is the boundary check, not a repeat of one.
			return List.of("employee_not_found");
		}

		List<String> setColumns = new ArrayList<>();
		List<Object> params = new ArrayList<>();
		for (String column : ALLOWED_COLUMNS) {
			if (payload.containsKey(column)) {
				setColumns.add(column + "=?");
				params.add(payload.get(column));
			}
		}
		if (payload.containsKey("password")) {
			String plain = String.valueOf(payload.get("password")).trim();
			if (!plain.isEmpty()) {
				setColumns.add("password_hash=?");
				params.add(this.bcrypt.encode(plain));
			}
		}

		String hireDate = employee.get("hire_date") == null ? "" : String.valueOf(employee.get("hire_date"));

		try {
			this.transactionTemplate.executeWithoutResult(status -> {
				if (!setColumns.isEmpty()) {
					List<Object> args = new ArrayList<>(params);
					args.add(id);
					args.add(companyId);
					this.jdbcTemplate.update(
							"UPDATE employees SET " + String.join(", ", setColumns)
									+ " WHERE id=? AND company_id=?",
							args.toArray());
				}
				Object shift = payload.get("shift_id");
				if (shift != null && asLong(shift) > 0) {
					// Appended, never replacing: assignments are a history, and
					// the effective date is today rather than the sheet's.
					this.jdbcTemplate.update(
							"INSERT INTO employee_shift_assignments (employee_id, shift_id, effective_from)"
									+ " VALUES (?, ?, ?)",
							id, asLong(shift), this.clock.todayAsString());
				}
				@SuppressWarnings("unchecked")
				Map<String, Object> salary = payload.get("salary") instanceof Map
						? (Map<String, Object>) payload.get("salary") : null;
				if (salary != null && !salary.isEmpty()) {
					applySalaryPatch(id, salary, hireDate);
				}
			});
		} catch (RuntimeException ex) {
			// PHP catches Throwable, rolls back and reports one opaque code.
			// The cause is deliberately not surfaced: the desktop client shows
			// error_messages to an operator, and a driver message is neither
			// translatable nor safe to render.
			return List.of("employee_update_failed");
		}
		return List.of();
	}

	/**
	 * {@code employee_excel_apply_salary_patch()}: patch the latest contract if
	 * there is one, otherwise insert a fresh one with the missing components
	 * zeroed.
	 *
	 * <p>Patching rather than versioning is legacy's choice and is reproduced:
	 * a sheet that corrects a typo in this month's basic salary edits the
	 * existing row instead of opening a new effective period, so history is
	 * not manufactured for what the operator means as a correction.
	 */
	private void applySalaryPatch(long employeeId, Map<String, Object> salary, String fallbackEffectiveFrom) {
		List<Map<String, Object>> latest = this.jdbcTemplate.queryForList(
				"SELECT * FROM salary_contracts WHERE employee_id=?"
						+ " ORDER BY effective_from DESC, id DESC LIMIT 1", employeeId);

		if (!latest.isEmpty()) {
			List<String> sets = new ArrayList<>();
			List<Object> params = new ArrayList<>();
			for (Map.Entry<String, String> entry : SALARY_COLUMNS.entrySet()) {
				if (salary.containsKey(entry.getKey())) {
					sets.add(entry.getValue() + "=?");
					params.add(toDouble(salary.get(entry.getKey())));
				}
			}
			if (sets.isEmpty()) {
				return;
			}
			params.add(latest.get(0).get("id"));
			this.jdbcTemplate.update(
					"UPDATE salary_contracts SET " + String.join(", ", sets) + " WHERE id=?",
					params.toArray());
			return;
		}

		// housing_allowance is hard-zeroed: the sheet has no column for it, and
		// PHP writes a literal 0 rather than leaving it to the column default.
		this.jdbcTemplate.update("""
				INSERT INTO salary_contracts (
					employee_id, basic_salary, housing_allowance, transport_allowance,
					food_allowance, risk_allowance, incentives, insurance_deduction,
					tax_deduction, advances_deduction, fund_deduction, penalty_deduction,
					effective_from
				) VALUES (?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
				employeeId,
				toDouble(salary.get("basic")),
				toDouble(salary.get("transport")),
				toDouble(salary.get("food_allowance")),
				toDouble(salary.get("risk_allowance")),
				toDouble(salary.get("incentives")),
				toDouble(salary.get("insurance_deduction")),
				toDouble(salary.get("tax_deduction")),
				toDouble(salary.get("advances_deduction")),
				toDouble(salary.get("fund_deduction")),
				toDouble(salary.get("penalty_deduction")),
				fallbackEffectiveFrom.isEmpty() ? this.clock.todayAsString() : fallbackEffectiveFrom);
	}

	private static Map<String, String> salaryColumns() {
		Map<String, String> columns = new LinkedHashMap<>();
		columns.put("basic", "basic_salary");
		columns.put("transport", "transport_allowance");
		columns.put("food_allowance", "food_allowance");
		columns.put("risk_allowance", "risk_allowance");
		columns.put("incentives", "incentives");
		columns.put("insurance_deduction", "insurance_deduction");
		columns.put("tax_deduction", "tax_deduction");
		columns.put("advances_deduction", "advances_deduction");
		columns.put("fund_deduction", "fund_deduction");
		columns.put("penalty_deduction", "penalty_deduction");
		return Map.copyOf(columns);
	}

	/** {@code (float) ($salary[$key] ?? 0)}. */
	private static double toDouble(Object value) {
		if (value instanceof Number number) {
			return number.doubleValue();
		}
		try {
			return value == null ? 0d : Double.parseDouble(String.valueOf(value).trim());
		} catch (NumberFormatException ex) {
			return 0d;
		}
	}

	private static long asLong(Object value) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		try {
			return value == null ? 0L : Long.parseLong(String.valueOf(value).trim());
		} catch (NumberFormatException ex) {
			return 0L;
		}
	}

}
