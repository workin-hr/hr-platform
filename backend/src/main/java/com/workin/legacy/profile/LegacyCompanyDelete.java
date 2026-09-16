package com.workin.legacy.profile;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.workin.legacy.wire.LegacyMessages;

/**
 * {@code helpers/company_delete_helper.php} -- the preview shown before a
 * company hard-deletes itself, and the cascade that performs it.
 *
 * <h2>The order of the cascade is load-bearing</h2>
 * <p>PHP's own comment says it: employee rows reference branches and
 * departments, so employee-dependent data and then employees must go before
 * the org structure. Two statements exist purely to break references rather
 * than to delete anything -- {@code notifications.from_employee_id = NULL} and
 * {@code departments.manager_id = NULL} -- and both run before the rows they
 * point at are removed. The sequence here is a transcription, not a
 * re-derivation.
 *
 * <h2>The swallowed failures are PHP's</h2>
 * <p>Several deletes sit inside {@code try { ... } catch (Throwable $ignored) {}}
 * in legacy: the three company-scoped tables that may not exist in every
 * deployment, the three join tables, and the final batch of company-scoped
 * tables. A failure there does not abort the cascade and does not surface to
 * the caller. That is reproduced, and it is the reason a partial cascade can
 * commit -- which is a real operational property of this endpoint, not an
 * accident of the port.
 *
 * <p>One failure is not swallowed: a lock failure. InnoDB answers a deadlock, and
 * a lock wait timeout under {@code innodb_rollback_on_timeout}, by rolling the
 * whole transaction back, so carrying on would commit the rest of the cascade in
 * a new transaction, without the rows deleted before it and without anything the
 * caller wrote first. Legacy swallows it; this rethrows it (D-245). A lock wait
 * timeout is recognised by its error code: Spring's default translator reads the
 * SQLSTATE, and MariaDB reports that one as {@code HY000}.
 *
 * <p><b>Every statement outside those inner catches is fatal.</b> PHP's outer
 * {@code try} ends in {@code catch (Throwable $e) { $pdo->rollBack(); throw $e; }},
 * so a failure in the {@code notifications} update or delete, anywhere in the
 * employee-owned join loop, in the {@code departments.manager_id} update, or in
 * the {@code department_branches} delete aborts the cascade and rolls the whole
 * transaction back. Only the statements wrapped in
 * {@code catch (Throwable $ignored)} are survivable.
 *
 * <p>What is special about the last statement is narrower: it is the only one
 * whose <em>success</em> is inspected. Deleting the company row is checked with
 * {@code rowCount() !== 1} and throws when it does not match, which catches a
 * silent no-op that would otherwise commit. An earlier revision of this comment
 * called it "the one statement that is not forgiving", conflating "explicitly
 * checked" with "able to abort"; the second set is much larger.
 *
 * <p>Either way the outcome is all-or-nothing at the company level: the cascade
 * removes the company or leaves everything, even though individual guarded
 * sub-deletes may have been skipped within a successful run.
 *
 * <h2>Rollback</h2>
 * <p>There is none. This is a hard delete of a tenant and everything under it,
 * inside one transaction, with no soft-delete column and no archive table. An
 * operator who needs the data back needs a database backup. Recorded rather
 * than mitigated, because legacy behaves this way today and D-058 puts the
 * burden of proof on the change.
 *
 * <h2>The device tables are deleted but not in the client preview</h2>
 * <p>{@link #DEVICE_OWNED} extends the cascade to the Phase-1-owned device
 * tables, which PHP knows nothing about. The client <b>preview</b> is
 * deliberately left alone: its key set and order are a client-visible contract
 * (D-111) that the Flutter clients render, and those clients cannot be
 * inspected from this repository (PMR-02). So a company admin deleting their
 * company from the app is not told how many device punches go with it, while
 * they are told about attendance. That under-reporting is a known gap awaiting
 * an owner decision, not an oversight. The platform administrator's delete page
 * does not share it: {@link #clearedTables} counts every table the cascade
 * deletes from, device tables included (D-245).
 */
@Service
public class LegacyCompanyDelete {

	/** {@code company_related_records_summary()}'s definitions, in its order. */
	private static final List<Related> DEFINITIONS = List.of(
			companyScoped("employees", "company_related_employees", "employees"),
			companyScoped("branches", "company_related_branches", "branches"),
			companyScoped("departments", "company_related_departments", "departments"),
			companyScoped("job_titles", "company_related_job_titles", "job_titles"),
			companyScoped("shifts", "company_related_shifts", "shifts"),
			throughEmployee("attendance", "company_related_attendance", "attendance"),
			throughEmployee("requests", "company_related_requests", "requests"),
			throughEmployee("payslips", "company_related_payslips", "payslips"),
			companyScoped("payroll_batches", "company_related_payroll_batches", "payroll_batches"),
			throughEmployee("advances", "company_related_advances", "advances"),
			throughEmployee("penalties", "company_related_penalties", "penalties"),
			companyScoped("notifications", "company_related_notifications", "notifications"),
			companyScoped("company_settings", "company_related_settings", "company_settings"),
			companyScoped("assets", "company_related_assets", "assets"),
			companyScoped("holidays", "company_related_holidays", "company_official_holidays"));

	/** The employee-join deletes, in {@code company_cascade_delete()}'s order. */
	private static final List<String> EMPLOYEE_OWNED = List.of(
			"payslips", "requests", "advances", "penalties", "leave_balance", "attendance",
			"employee_schedules", "employee_shift_assignments", "salary_contracts", "employee_docs",
			"complaints", "push_tokens", "hr_permissions");

	/** Company-scoped rows that may still reference employees; failures ignored. */
	private static final List<String> COMPANY_OWNED_EARLY = List.of(
			"assets", "administrative_decisions", "workforce_planning");

	/**
	 * Phase-1-owned device tables (D-164), children first.
	 *
	 * <p>Not part of {@code company_delete_helper.php} -- they do not exist in
	 * PHP, so including them is not a parity divergence; leaving them out
	 * would be a real one, because a deleted company's device registry, PIN
	 * bindings, raw punches and operation logs would outlive it. The
	 * globally-unique serial matters too: without this, a terminal whose
	 * company was deleted could never be claimed again. An agent goes with its
	 * company for the same reason a device does, and more urgently: its token
	 * would otherwise go on authenticating for a company that no longer exists.
	 *
	 * <p>Deleted through {@code ignoringFailure} like the other company-scoped
	 * batches, because a deployment that has not provisioned these tables yet
	 * (R-023, Q7 is still open) must not have its company deletion aborted by
	 * their absence. {@code unclaimed_device_sightings} is deliberately absent
	 * from this list: an unclaimed serial belongs to no company.
	 */
	private static final List<String> DEVICE_OWNED = List.of(
			"device_punches", "device_operation_logs", "device_malformed_punches",
			"device_assignment_history", "employee_device_identities", "attendance_devices",
			"device_agents");

	/** The final company-scoped batch; failures ignored. */
	private static final List<String> COMPANY_OWNED_LATE = List.of(
			"payroll_batches", "company_settings", "request_types", "exception_types",
			"company_official_holidays", "job_titles", "shifts", "departments", "branches");

	/**
	 * The foreign keys with {@code ON DELETE CASCADE} that remove rows the table's own
	 * statement does not reach: a complaint the company filed rather than an employee,
	 * a payslip in one of its batches, an assignment to one of its shifts, a link to one
	 * of its departments, and another company's notification to or from one of its
	 * employees. Declared before {@link #COUNTED}, which is built from it.
	 */
	private static final Map<String, List<Path>> ALSO_THROUGH_KEYS = Map.of(
			"notifications", List.of(new Path("to_employee_id", "employees"), new Path("from_employee_id", "employees")),
			"complaints", List.of(new Path("company_id", "companies")),
			"payslips", List.of(new Path("batch_id", "payroll_batches")),
			"employee_shift_assignments", List.of(new Path("shift_id", "shifts")),
			"department_branches", List.of(new Path("department_id", "departments")));

	/**
	 * Every table the delete removes rows from, in the cascade's order, with each way its
	 * rows go with the company: the cascade's own statement for the table, and the keys in
	 * {@link #ALSO_THROUGH_KEYS}. Counting one path per table missed a support complaint
	 * with no employee, which only {@code complaints.company_id}'s key removes.
	 * {@code LegacyCompanyDeleteCascadeTest} holds this list to the statements the cascade
	 * runs, to every cascading key the schema declares, and to the rows a delete removes.
	 */
	private static final List<Counted> COUNTED = counted();

	/** A column whose value makes a row go: the company's id, or the id of a parent row that goes. */
	private record Path(String column, String parent) {

		String condition() {
			return "companies".equals(parent)
					? column + " = ?"
					: column + " IN (SELECT id FROM " + parent + " WHERE company_id = ?)";
		}
	}

	private record Counted(String table, List<Path> paths) {

		String sql() {
			return "SELECT COUNT(*) FROM " + table + " WHERE "
					+ String.join(" OR ", paths.stream().map(Path::condition).toList());
		}

		List<String> needs() {
			List<String> needs = new ArrayList<>(List.of(table));
			paths.stream().map(Path::parent).filter(parent -> !"companies".equals(parent)).forEach(needs::add);
			return needs;
		}
	}

	private static List<Counted> counted() {
		List<Counted> counted = new ArrayList<>();
		counted.add(rows("notifications", "company_id", "companies"));
		EMPLOYEE_OWNED.forEach(table -> counted.add(rows(table, "employee_id", "employees")));
		COMPANY_OWNED_EARLY.forEach(table -> counted.add(rows(table, "company_id", "companies")));
		DEVICE_OWNED.forEach(table -> counted.add(rows(table, "company_id", "companies")));
		counted.add(rows("employees", "company_id", "companies"));
		counted.add(rows("department_branches", "branch_id", "branches"));
		counted.add(rows("job_title_sections", "job_title_id", "job_titles"));
		counted.add(rows("section_departments", "department_id", "departments"));
		counted.add(rows("company_setting_values", "company_setting_id", "company_settings"));
		COMPANY_OWNED_LATE.forEach(table -> counted.add(rows(table, "company_id", "companies")));
		return List.copyOf(counted);
	}

	/** The path the cascade's own statement takes, and any the table's keys add. */
	private static Counted rows(String table, String column, String parent) {
		List<Path> paths = new ArrayList<>(List.of(new Path(column, parent)));
		paths.addAll(ALSO_THROUGH_KEYS.getOrDefault(table, List.of()));
		return new Counted(table, List.copyOf(paths));
	}

	private final JdbcTemplate jdbcTemplate;
	private final TransactionTemplate transactions;
	private final LegacyMessages messages;

	public LegacyCompanyDelete(DataSource legacyDataSource, LegacyMessages messages) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
		this.transactions = new TransactionTemplate(new DataSourceTransactionManager(legacyDataSource));
		this.messages = messages;
	}

	private record Related(String key, String labelKey, String sql) {
	}

	private static Related companyScoped(String key, String labelKey, String table) {
		return new Related(key, labelKey, "SELECT COUNT(*) FROM " + table + " WHERE company_id = ?");
	}

	private static Related throughEmployee(String key, String labelKey, String table) {
		return new Related(key, labelKey,
				"SELECT COUNT(*) FROM " + table + " t"
						+ " INNER JOIN employees e ON e.id = t.employee_id WHERE e.company_id = ?");
	}

	/**
	 * {@code company_delete_preview_payload()}. Key order is PHP's insertion
	 * order and is part of the wire contract (D-074).
	 */
	public Map<String, Object> previewPayload(long companyId, String locale) {
		List<Map<String, Object>> related = summary(companyId, locale);
		long total = 0;
		for (Map<String, Object> item : related) {
			total += (Long) item.get("count");
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("company_id", companyId);
		payload.put("has_related_records", !related.isEmpty());
		payload.put("total_related_records", total);
		payload.put("related_records", related);
		return payload;
	}

	/**
	 * {@code company_related_records_summary()}: a count per definition, with a
	 * failing count treated as zero, and any zero omitted entirely -- so the
	 * list is variable-length and a company with nothing under it gets
	 * {@code []}.
	 */
	public List<Map<String, Object>> summary(long companyId, String locale) {
		List<Map<String, Object>> items = new ArrayList<>();
		for (Related definition : DEFINITIONS) {
			long count;
			try {
				Long value = jdbcTemplate.queryForObject(definition.sql(), Long.class, companyId);
				count = value == null ? 0L : value;
			} catch (RuntimeException ignored) {
				// catch (Throwable $ignored) { $count = 0; } -- a table that does
				// not exist in this deployment is skipped, not an error.
				count = 0L;
			}
			if (count <= 0) {
				continue;
			}
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("key", definition.key());
			item.put("label", messages.translate(locale, definition.labelKey(), null));
			item.put("count", count);
			items.add(item);
		}
		return items;
	}

	/** One table the cascade deletes from, and this company's rows in it. */
	public record ClearedTable(String table, long rows) {
	}

	/** The tables {@link #clearedTables} counts, in the cascade's order. */
	public static List<String> countedTables() {
		return COUNTED.stream().map(Counted::table).toList();
	}

	/** Every way a counted table's rows go, as {@code table.column -> parent}. */
	public static Set<String> countedPaths() {
		Set<String> paths = new HashSet<>();
		for (Counted count : COUNTED) {
			count.paths().forEach(path -> paths.add(count.table() + "." + path.column() + " -> " + path.parent()));
		}
		return paths;
	}

	/**
	 * What the delete would remove: every row, by table, that the cascade's statements
	 * or the foreign keys cascading from them take with the company, device tables
	 * included, in the cascade's order.
	 *
	 * <p>Not {@link #summary}, whose fifteen keys are the client preview's
	 * contract. A table with no rows is left out, and so is a table not deployed
	 * here, which is asked of {@code information_schema} as
	 * {@link #deleteFromOptionalTable} does. Any other failure is thrown, unlike in
	 * {@code summary}: a count that silently read zero would show an operator less
	 * than the delete removes.
	 */
	public List<ClearedTable> clearedTables(long companyId) {
		Set<String> deployed = new HashSet<>(jdbcTemplate.queryForList(
				"SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()",
				String.class));
		List<ClearedTable> cleared = new ArrayList<>();
		for (Counted count : COUNTED) {
			if (!deployed.containsAll(count.needs())) {
				continue;
			}
			Object[] ids = new Object[count.paths().size()];
			Arrays.fill(ids, companyId);
			Long rows = jdbcTemplate.queryForObject(count.sql(), Long.class, ids);
			if (rows != null && rows > 0) {
				cleared.add(new ClearedTable(count.table(), rows));
			}
		}
		return cleared;
	}

	/**
	 * {@code company_cascade_delete()}.
	 *
	 * <p>The preview is computed <b>before</b> the transaction opens and is what
	 * comes back, so the response describes what was there rather than what
	 * survived.
	 *
	 * @return the pre-delete summary, as PHP returns {@code $preview}
	 */
	public List<Map<String, Object>> cascadeDelete(long companyId, String locale) {
		List<Map<String, Object>> preview = summary(companyId, locale);
		transactions.executeWithoutResult(status -> deleteEverything(companyId));
		return preview;
	}

	/**
	 * The same cascade in the caller's transaction instead of one of its own, for a
	 * caller whose own writes must commit or roll back with it: the platform
	 * administrator's audit row.
	 *
	 * <p>{@link #cascadeDelete} cannot serve that caller. Its
	 * {@code DataSourceTransactionManager} does not recognise a JPA transaction on the
	 * same connection as one to join, because {@code JpaTransactionManager} exposes the
	 * connection without marking it transaction-active. So it begins its "own"
	 * transaction on that connection and commits it, and the caller's earlier writes
	 * commit with it.
	 *
	 * @throws IllegalTransactionStateException when no transaction is active
	 */
	public void cascadeDeleteInCurrentTransaction(long companyId) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalTransactionStateException(
					"the company cascade must join the caller's transaction");
		}
		deleteEverything(companyId);
	}

	private void deleteEverything(long companyId) {
		jdbcTemplate.update(
				"UPDATE notifications SET from_employee_id = NULL WHERE company_id = ?", companyId);
		jdbcTemplate.update("DELETE FROM notifications WHERE company_id = ?", companyId);

		for (String table : EMPLOYEE_OWNED) {
			jdbcTemplate.update(
					"DELETE t FROM " + table + " t"
							+ " INNER JOIN employees e ON e.id = t.employee_id WHERE e.company_id = ?",
					companyId);
		}

		jdbcTemplate.update("UPDATE departments SET manager_id = NULL WHERE company_id = ?", companyId);

		for (String table : COMPANY_OWNED_EARLY) {
			ignoringFailure("DELETE FROM " + table + " WHERE company_id = ?", companyId);
		}

		// Before employees and branches go, though nothing here has a
		// foreign key to either -- the ordering is for readers, not the
		// database.
		//
		// deleteFromOptionalTable, NOT ignoringFailure: these four are the
		// tables whose survival is dangerous rather than merely untidy. An
		// attendance_devices row is what makes a serial recognised, so one
		// surviving row keeps a terminal ingesting punches against a company
		// that no longer exists. Swallowing every RuntimeException cannot
		// tell "not deployed here" from "the delete was refused", and the
		// second must roll the cascade back.
		for (String table : DEVICE_OWNED) {
			deleteFromOptionalTable(table, companyId);
		}

		jdbcTemplate.update("DELETE FROM employees WHERE company_id = ?", companyId);

		jdbcTemplate.update("""
				DELETE db FROM department_branches db
				INNER JOIN branches b ON b.id = db.branch_id
				WHERE b.company_id = ?""", companyId);

		ignoringFailure("""
				DELETE jts FROM job_title_sections jts
				INNER JOIN job_titles jt ON jt.id = jts.job_title_id
				WHERE jt.company_id = ?""", companyId);
		ignoringFailure("""
				DELETE sd FROM section_departments sd
				INNER JOIN departments d ON d.id = sd.department_id
				WHERE d.company_id = ?""", companyId);
		ignoringFailure("""
				DELETE csv FROM company_setting_values csv
				INNER JOIN company_settings cs ON cs.id = csv.company_setting_id
				WHERE cs.company_id = ?""", companyId);

		for (String table : COMPANY_OWNED_LATE) {
			ignoringFailure("DELETE FROM " + table + " WHERE company_id = ?", companyId);
		}

		if (jdbcTemplate.update("DELETE FROM companies WHERE id = ?", companyId) != 1) {
			// throw new RuntimeException('company_delete_failed') -- the one
			// statement whose failure rolls the whole cascade back.
			throw new IllegalStateException("company_delete_failed");
		}
	}

	/**
	 * Delete from a table that may not exist in this deployment -- tolerating
	 * its absence, but never its failure.
	 *
	 * <p>Absence is established by asking {@code information_schema} rather
	 * than by catching an exception, because a catch cannot distinguish a
	 * missing table from a missing DELETE grant, a lock timeout, or a trigger
	 * refusing the row. Only the first is safe to ignore; the rest have to
	 * abort so the surrounding transaction rolls back and an operator sees a
	 * failure instead of a half-deleted tenant.
	 */
	private void deleteFromOptionalTable(String table, long companyId) {
		Number found = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM information_schema.TABLES"
						+ " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
				Number.class, table);
		if (found == null || found.intValue() == 0) {
			return;
		}
		jdbcTemplate.update("DELETE FROM " + table + " WHERE company_id = ?", companyId);
	}

	private void ignoringFailure(String sql, long companyId) {
		try {
			jdbcTemplate.update(sql, companyId);
		} catch (RuntimeException failure) {
			if (isLockFailure(failure)) {
				// Not PHP's (see the class javadoc): the server may already have rolled
				// the transaction back, and what follows would commit without it.
				throw failure;
			}
			// catch (Throwable $ignored) {} -- deliberately silent, see the class
			// javadoc. A table missing from this deployment must not abort the
			// cascade, and legacy reports nothing either.
		}
	}

	/** MySQL's lock wait timeout, deadlock and {@code NOWAIT} errors. */
	private static final Set<Integer> LOCK_ERRORS = Set.of(1205, 1213, 3572);

	private static boolean isLockFailure(RuntimeException failure) {
		return failure instanceof PessimisticLockingFailureException
				|| failure instanceof DataAccessException access
						&& access.getMostSpecificCause() instanceof SQLException sql
						&& LOCK_ERRORS.contains(sql.getErrorCode());
	}
}
