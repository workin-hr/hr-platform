package com.workin.legacy.employees.spreadsheet;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyPhpArray;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.employees.LegacyHrPeerCredentials;

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
 *
 * <h2>Chunks first, rows when a chunk fails (D-294)</h2>
 * <p>Everything validation reads is read before the first row
 * ({@link LegacyEmployeeUpdateAnalyzer#prepare}), and rows are written in
 * chunks of up to {@link LegacyEmployeeUpdateSheetStore#CHUNK}: one
 * transaction per chunk, one boundary re-read for the chunk, and each kind
 * of write sent as one JDBC batch. That is the only thing about the per-row
 * transactions that changes, and only while every row of the chunk
 * succeeds -- which, when all of them commit, is indistinguishable from
 * each committing alone. The moment one write fails, or the re-read
 * disagrees with what the rows were validated against, the whole chunk is
 * rolled back and replayed <b>one row per transaction</b>, validated again
 * in order, which is exactly PHP's loop: the failing row rolls back only
 * itself and every other row lands. The cost of a failure is that chunk's
 * rows at per-row cost; the cost of success is a handful of round trips per
 * chunk instead of a dozen per row. The one failure that is not replayed is
 * the commit itself: whether the chunk landed is then unknown, so its rows
 * are reported failed, as PHP reports a row whose commit failed.
 */
@Component
public class LegacyEmployeeBulkUpdater {

	private static final Logger LOG = LoggerFactory.getLogger(LegacyEmployeeBulkUpdater.class);

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

	private final LegacyEmployeeUpdateAnalyzer analyzer;

	private final LegacyEmployeeUpdateSheetStore sheetStore;

	private final LegacyClock clock;

	private final JdbcTemplate jdbcTemplate;

	private final TransactionTemplate transactionTemplate;

	/** {@code password_hash($plain, PASSWORD_BCRYPT)} -- the same encoder the create sheet uses. */
	private final PasswordEncoder bcrypt;

	/** Rows per write transaction; {@link LegacyEmployeeUpdateSheetStore#CHUNK} outside tests. */
	private final int chunkSize;

	@Autowired
	public LegacyEmployeeBulkUpdater(
			LegacyEmployeeUpdateAnalyzer analyzer, LegacyEmployeeUpdateSheetStore sheetStore,
			LegacyClock clock, DataSource legacyDataSource, PasswordEncoder bcrypt) {
		this(analyzer, sheetStore, clock, legacyDataSource, bcrypt, LegacyEmployeeUpdateSheetStore.CHUNK);
	}

	/** With a smaller chunk, so a test can put a failing row mid-chunk and cross a boundary cheaply. */
	LegacyEmployeeBulkUpdater(
			LegacyEmployeeUpdateAnalyzer analyzer, LegacyEmployeeUpdateSheetStore sheetStore,
			LegacyClock clock, DataSource legacyDataSource, PasswordEncoder bcrypt, int chunkSize) {
		if (chunkSize < 1 || chunkSize > LegacyEmployeeUpdateSheetStore.CHUNK) {
			throw new IllegalArgumentException("chunk size out of range: " + chunkSize);
		}
		this.analyzer = analyzer;
		this.sheetStore = sheetStore;
		this.clock = clock;
		this.bcrypt = bcrypt;
		this.chunkSize = chunkSize;
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
		// Its own transaction manager over the same DataSource: each chunk's
		// (or, on replay, each row's) writes commit independently, which an
		// ambient request transaction would defeat by folding them all into one.
		this.transactionTemplate =
				new TransactionTemplate(new DataSourceTransactionManager(legacyDataSource));
	}

	/**
	 * {@code employee_excel_update_rows()}.
	 *
	 * @return {@code updated}, {@code failed} and {@code updated_ids}, the
	 *         three keys the endpoint returns unchanged
	 */
	public Map<String, Object> updateRows(LegacyRequestContext context, LegacyPhpArray rows,
			LegacyEmployeeSpreadsheetLookups lookups) {

		// Iterated as a PHP array, not a Java list: row_index is $index + 1 over
		// the *submitted keys*, so a JSON object or a sparse array numbers its
		// rows the way PHP would rather than by position.
		List<LegacyPhpArray.Entry> entries = rows.entries();
		List<Map<String, Object>> sheetRows = new ArrayList<>(entries.size());
		for (LegacyPhpArray.Entry entry : entries) {
			sheetRows.add(rowOf(entry.value()));
		}

		// Checked before parsing, as PHP does: a second row for the same code
		// fails on the duplicate alone, whatever else is wrong with it. It
		// depends on nothing but the order, so it is settled once here.
		boolean[] duplicate = new boolean[sheetRows.size()];
		Set<String> seenCodes = new HashSet<>();
		for (int position = 0; position < sheetRows.size(); position++) {
			String code = codeOf(sheetRows.get(position));
			duplicate[position] = !code.isEmpty() && !seenCodes.add(code);
		}

		Batch batch = new Batch(context, this.analyzer.prepare(sheetRows, context.companyId(), lookups),
				sheetRows, duplicate);
		for (int from = 0; from < sheetRows.size(); from += this.chunkSize) {
			applyChunk(batch, from, Math.min(sheetRows.size(), from + this.chunkSize));
		}

		List<Map<String, Object>> failed = new ArrayList<>();
		List<Object> updatedIds = new ArrayList<>();
		long updated = 0;
		for (int position = 0; position < entries.size(); position++) {
			List<String> errors = batch.outcomes().get(position);
			if (errors.isEmpty()) {
				updated++;
				updatedIds.add(batch.updatedIds().get(position));
			} else {
				failed.add(failure(entries.get(position), errors, sheetRows.get(position)));
			}
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("updated", updated);
		result.put("failed", failed);
		result.put("updated_ids", updatedIds);
		return result;
	}

	/** One request's rows and what has been decided about them, by position. */
	private record Batch(LegacyRequestContext context, LegacyEmployeeUpdateSheet sheet,
			List<Map<String, Object>> rows, boolean[] duplicate, List<List<String>> outcomes,
			Map<Integer, Object> updatedIds, Map<Integer, String[]> hashes) {

		/** Outcomes start null, meaning undecided; an empty list is a row that applied. */
		Batch(LegacyRequestContext context, LegacyEmployeeUpdateSheet sheet, List<Map<String, Object>> rows,
				boolean[] duplicate) {
			this(context, sheet, rows, duplicate, new ArrayList<>(Collections.nCopies(rows.size(), null)),
					new HashMap<>(), new HashMap<>());
		}
	}

	/**
	 * One row, validated and ready to write -- or, when {@link #errors} is not
	 * empty, the reason it will not be.
	 */
	private record Planned(int position, List<String> errors, Map<String, Object> payload,
			Map<String, Object> employee, List<String> setColumns, List<Object> params) {

		static Planned failed(int position, List<String> errors) {
			return new Planned(position, errors, null, null, null, null);
		}

		long id() {
			return asLong(this.payload.get("id"));
		}
	}

	/**
	 * Rows {@code from} (inclusive) to {@code to}: validated in order against
	 * the holders the rows before them left, written as one transaction, and
	 * replayed one row per transaction if that transaction does not commit.
	 */
	private void applyChunk(Batch batch, int from, int to) {
		LegacyEmployeeUpdateSheet sheet = batch.sheet();
		int mark = sheet.mark();
		List<Planned> writes = new ArrayList<>();
		for (int position = from; position < to; position++) {
			Planned planned = plan(batch, position);
			if (!planned.errors().isEmpty()) {
				batch.outcomes().set(position, planned.errors());
				continue;
			}
			// Claimed now so the next row in this chunk is validated as PHP
			// validates it: after this row's phone was written.
			claim(sheet, planned);
			writes.add(planned);
		}
		if (writes.isEmpty()) {
			return;
		}
		try {
			this.transactionTemplate.executeWithoutResult(status -> write(batch.context(), sheet, writes));
			for (Planned planned : writes) {
				succeeded(batch, planned);
			}
			return;
		} catch (TransactionSystemException ex) {
			// The commit (or the rollback) itself failed, so whether the chunk
			// landed is unknown. Replaying could apply a shift assignment
			// twice; PHP, whose commit fails the same way, reports the row
			// failed. Every row of the chunk is reported so, and none is retried.
			LOG.warn("employees.update_bulk.chunk_commit_failed tenant_id={} first_row={} rows={} cause={}",
					batch.context().companyId(), from + 1, to - from, ex.getClass().getSimpleName());
			sheet.rollBackTo(mark);
			for (Planned planned : writes) {
				batch.outcomes().set(planned.position(), List.of("employee_update_failed"));
			}
			return;
		} catch (RuntimeException ex) {
			// Either a write failed or the re-read disagreed with the
			// validation; the transaction is rolled back either way, and the
			// replay below decides each row's own outcome. Logged because a
			// replayed chunk costs its rows' per-row round trips: an operator
			// seeing slow sheets needs to know it happened. The class only --
			// a driver message can quote the row's values.
			LOG.warn("employees.update_bulk.chunk_replayed tenant_id={} first_row={} rows={} cause={}",
					batch.context().companyId(), from + 1, to - from, ex.getClass().getSimpleName());
			sheet.rollBackTo(mark);
		}
		for (int position = from; position < to; position++) {
			batch.outcomes().set(position, null);
			Planned planned = plan(batch, position);
			if (!planned.errors().isEmpty()) {
				batch.outcomes().set(position, planned.errors());
				continue;
			}
			List<String> errors = writeAlone(batch.context(), sheet, planned);
			if (errors.isEmpty()) {
				claim(sheet, planned);
				succeeded(batch, planned);
			} else {
				batch.outcomes().set(position, errors);
			}
		}
	}

	private static void succeeded(Batch batch, Planned planned) {
		batch.outcomes().set(planned.position(), List.of());
		batch.updatedIds().put(planned.position(), planned.payload().get("id"));
	}

	private static void claim(LegacyEmployeeUpdateSheet sheet, Planned planned) {
		if (planned.payload().containsKey("phone")) {
			sheet.claimPhone(planned.employee(), planned.payload().get("phone"), planned.payload().get("country_code"));
		}
	}

	/**
	 * {@code employee_excel_row_to_update_payload()} and the part of
	 * {@code employee_excel_apply_update()} that precedes its transaction,
	 * against the sheet's reads. The peer rule is decided here against the
	 * employee as the sheet read it, so a refused row is never hashed, and
	 * decided again against the transaction's own re-read in {@link #write}.
	 */
	private Planned plan(Batch batch, int position) {
		if (batch.duplicate()[position]) {
			return Planned.failed(position, List.of("employee_code_duplicate_in_file"));
		}
		Map<String, Object> row = batch.rows().get(position);
		LegacyEmployeeUpdateAnalyzer.Parsed parsed = this.analyzer.rowToUpdatePayload(row, batch.sheet());
		if (!parsed.errors().isEmpty()) {
			return Planned.failed(position, parsed.errors());
		}
		Map<String, Object> payload = parsed.payload();
		long id = asLong(payload.get("id"));
		Map<String, Object> employee = batch.sheet().employee(codeOf(row));
		if (id < 1 || employee == null) {
			return Planned.failed(position, List.of("employee_not_found"));
		}
		// D-289: the same peer rule update.php applies. The sheet repeats a
		// phone it did not change, so only a value that would change counts --
		// and working that out resolves the stored phone, so it is asked only
		// of a row the rule could refuse at all.
		if (refuses(batch.context(), id, employee, payload, batch.sheet())) {
			return Planned.failed(position, List.of("forbidden"));
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
				params.add(hash(batch, position, plain));
			}
		}
		return new Planned(position, List.of(), payload, employee, setColumns, params);
	}

	/** bcrypt once per row, however many times a failed chunk makes it be planned. */
	private String hash(Batch batch, int position, String plain) {
		String[] cached = batch.hashes().get(position);
		if (cached != null && cached[0].equals(plain)) {
			return cached[1];
		}
		String hashed = this.bcrypt.encode(plain);
		batch.hashes().put(position, new String[] {plain, hashed});
		return hashed;
	}

	private static boolean refuses(LegacyRequestContext context, long id, Map<String, Object> employee,
			Map<String, Object> payload, LegacyEmployeeUpdateSheet sheet) {
		Object role = employee.get("role");
		return LegacyHrPeerCredentials.refuses(context, id, role, LegacyHrPeerCredentials.FIELDS)
				&& LegacyHrPeerCredentials.refuses(context, id, role, changedCredentials(payload, employee, sheet));
	}

	/**
	 * One row in its own transaction -- PHP's {@code apply_update()} exactly.
	 *
	 * @return the errors, empty when the row applied
	 */
	private List<String> writeAlone(LegacyRequestContext context, LegacyEmployeeUpdateSheet sheet, Planned planned) {
		try {
			this.transactionTemplate.executeWithoutResult(status -> write(context, sheet, List.of(planned)));
			return List.of();
		} catch (RowRefused refused) {
			return List.of(refused.error);
		} catch (RuntimeException ex) {
			// PHP catches Throwable, rolls back and reports one opaque code.
			// The cause is deliberately not surfaced: the desktop client shows
			// error_messages to an operator, and a driver message is neither
			// translatable nor safe to render.
			return List.of("employee_update_failed");
		}
	}

	/** A row the boundary re-read no longer allows; rolls its transaction back. */
	private static final class RowRefused extends RuntimeException {

		private static final long serialVersionUID = 1L;

		private final String error;

		RowRefused(String error) {
			super(error, null, false, false);
			this.error = error;
		}
	}

	/**
	 * The writes of {@code rows}, inside the caller's transaction.
	 *
	 * <p>First the boundary re-read, one statement for all of them: each
	 * employee re-read under the company predicate even though the sheet
	 * already matched the code within this company, and the peer rule asked
	 * again of what was just read -- the row about to be written, not the one
	 * the request started from. A row that no longer passes throws, which
	 * rolls the transaction back: alone, that is the row's own outcome; in a
	 * chunk, it sends the chunk to be replayed a row at a time.
	 *
	 * <p>Then each kind of write as one batch, in PHP's order: employees,
	 * shift assignments, salary patches. Grouping by statement reorders
	 * writes <em>between</em> rows, never within one; rows are independent
	 * except through the phone's unique index, and a collision that ordering
	 * created fails the chunk into the row-at-a-time replay, which writes in
	 * sheet order.
	 */
	private void write(LegacyRequestContext context, LegacyEmployeeUpdateSheet sheet, List<Planned> rows) {
		long companyId = context.companyId();
		List<Long> ids = new ArrayList<>(rows.size());
		for (Planned planned : rows) {
			ids.add(planned.id());
		}
		Map<Long, Map<String, Object>> targets = this.sheetStore.applyTargets(ids, companyId);

		Map<String, List<Object[]>> employeeUpdates = new LinkedHashMap<>();
		List<Object[]> shiftInserts = new ArrayList<>();
		Map<String, List<Object[]>> salaryUpdates = new LinkedHashMap<>();
		List<Object[]> salaryInserts = new ArrayList<>();
		String today = this.clock.todayAsString();

		for (Planned planned : rows) {
			long id = planned.id();
			Map<String, Object> target = targets.get(id);
			if (target == null) {
				throw new RowRefused("employee_not_found");
			}
			if (refuses(context, id, target, planned.payload(), sheet)) {
				throw new RowRefused("forbidden");
			}
			if (!planned.setColumns().isEmpty()) {
				List<Object> args = new ArrayList<>(planned.params());
				args.add(id);
				args.add(companyId);
				employeeUpdates.computeIfAbsent(
						"UPDATE employees SET " + String.join(", ", planned.setColumns()) + " WHERE id=? AND company_id=?",
						sql -> new ArrayList<>()).add(args.toArray());
			}
			Object shift = planned.payload().get("shift_id");
			if (shift != null && asLong(shift) > 0) {
				// Appended, never replacing: assignments are a history, and
				// the effective date is today rather than the sheet's.
				shiftInserts.add(new Object[] {id, asLong(shift), today});
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> salary = planned.payload().get("salary") instanceof Map
					? (Map<String, Object>) planned.payload().get("salary") : null;
			if (salary != null && !salary.isEmpty()) {
				salaryPatch(id, salary, target, today, salaryUpdates, salaryInserts);
			}
		}

		employeeUpdates.forEach(this::batch);
		batch("INSERT INTO employee_shift_assignments (employee_id, shift_id, effective_from) VALUES (?, ?, ?)",
				shiftInserts);
		salaryUpdates.forEach(this::batch);
		batch(SALARY_INSERT, salaryInserts);
	}

	/** One statement, every row's arguments, one batch. */
	private void batch(String sql, List<Object[]> args) {
		if (args.isEmpty()) {
			return;
		}
		int[] counts = this.jdbcTemplate.batchUpdate(sql, args);
		for (int count : counts) {
			if (count == Statement.EXECUTE_FAILED) {
				throw new IllegalStateException("a batched statement failed: " + sql);
			}
		}
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

	private static String codeOf(Map<String, Object> row) {
		return LegacyEmployeeSpreadsheetErrors.normalizeEmployeeCode(
				row.get("employee_code") == null ? "" : String.valueOf(row.get("employee_code")));
	}

	/**
	 * The credential fields this row would actually change: a password the
	 * apply step would hash, and a phone or country code that differs from
	 * the stored one once both are in the form the sheet resolves to -- a
	 * stored {@code 201012345678} is the sheet's {@code 01012345678}. The bulk
	 * sheet has no active-flag column.
	 */
	private static List<String> changedCredentials(Map<String, Object> payload, Map<String, Object> employee,
			LegacyEmployeeUpdateSheet sheet) {
		List<String> changed = new ArrayList<>();
		if (payload.containsKey("password") && !String.valueOf(payload.get("password")).trim().isEmpty()) {
			changed.add("password");
		}
		if (!payload.containsKey("phone") && !payload.containsKey("country_code")) {
			return changed;
		}
		String[] stored = LegacyEmployeeUpdateAnalyzer.storedPhoneAsSheetResolves(employee, sheet);
		List<String> fields = List.of("phone", "country_code");
		for (int index = 0; index < fields.size(); index++) {
			String field = fields.get(index);
			String current = stored == null ? LegacyValues.toPhpString(employee.get(field)) : stored[index];
			if (payload.containsKey(field) && !LegacyValues.toPhpString(payload.get(field)).equals(current)) {
				changed.add(field);
			}
		}
		return changed;
	}

	/** {@code employee_excel_apply_salary_patch()}'s INSERT. */
	private static final String SALARY_INSERT = """
			INSERT INTO salary_contracts (
				employee_id, basic_salary, housing_allowance, transport_allowance,
				food_allowance, risk_allowance, incentives, insurance_deduction,
				tax_deduction, advances_deduction, fund_deduction, penalty_deduction,
				effective_from
			) VALUES (?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

	/**
	 * {@code employee_excel_apply_salary_patch()}: patch the latest contract if
	 * there is one, otherwise insert a fresh one with the missing components
	 * zeroed. Collected into the batches rather than executed; {@code target}
	 * is the transaction's own re-read, carrying the latest contract and the
	 * hire date as they were before this row's writes.
	 *
	 * <p>Patching rather than versioning is legacy's choice and is reproduced:
	 * a sheet that corrects a typo in this month's basic salary edits the
	 * existing row instead of opening a new effective period, so history is
	 * not manufactured for what the operator means as a correction.
	 */
	private static void salaryPatch(long employeeId, Map<String, Object> salary, Map<String, Object> target,
			String today, Map<String, List<Object[]>> updates, List<Object[]> inserts) {
		Object latest = target.get("contract_id");
		if (latest != null) {
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
			params.add(latest);
			updates.computeIfAbsent("UPDATE salary_contracts SET " + String.join(", ", sets) + " WHERE id=?",
					sql -> new ArrayList<>()).add(params.toArray());
			return;
		}

		// housing_allowance is hard-zeroed: the sheet has no column for it, and
		// PHP writes a literal 0 rather than leaving it to the column default.
		String hireDate = target.get("hire_date") == null ? "" : String.valueOf(target.get("hire_date"));
		inserts.add(new Object[] {
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
				hireDate.isEmpty() ? today : hireDate});
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
