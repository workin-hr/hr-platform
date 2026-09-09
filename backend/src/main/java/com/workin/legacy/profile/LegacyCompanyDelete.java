package com.workin.legacy.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
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
 * the caller. That is reproduced exactly, and it is the reason a partial
 * cascade can commit -- which is a real operational property of this endpoint,
 * not an accident of the port.
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
 * <h2>The device tables are deleted but not previewed</h2>
 * <p>{@link #DEVICE_OWNED} extends the cascade to the Phase-1-owned device
 * tables, which PHP knows nothing about. The <b>preview</b> is deliberately
 * left alone: its key set and order are a client-visible contract (D-111)
 * that the Flutter clients render, and those clients cannot be inspected from
 * this repository (PMR-02). So a company admin deleting their company is not
 * told how many device punches go with it, while they are told about
 * attendance. That under-reporting is a known gap awaiting an owner decision,
 * not an oversight.
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
	 * company was deleted could never be claimed again.
	 *
	 * <p>Deleted through {@code ignoringFailure} like the other company-scoped
	 * batches, because a deployment that has not provisioned these tables yet
	 * (R-023, Q7 is still open) must not have its company deletion aborted by
	 * their absence. {@code unclaimed_device_sightings} is deliberately absent
	 * from this list: an unclaimed serial belongs to no company.
	 */
	private static final List<String> DEVICE_OWNED = List.of(
			"device_punches", "device_operation_logs", "employee_device_identities", "attendance_devices");

	/** The final company-scoped batch; failures ignored. */
	private static final List<String> COMPANY_OWNED_LATE = List.of(
			"payroll_batches", "company_settings", "request_types", "exception_types",
			"company_official_holidays", "job_titles", "shifts", "departments", "branches");

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
		transactions.executeWithoutResult(status -> {
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
			for (String table : DEVICE_OWNED) {
				ignoringFailure("DELETE FROM " + table + " WHERE company_id = ?", companyId);
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
		});
		return preview;
	}

	private void ignoringFailure(String sql, long companyId) {
		try {
			jdbcTemplate.update(sql, companyId);
		} catch (RuntimeException ignored) {
			// catch (Throwable $ignored) {} -- deliberately silent, see the class
			// javadoc. A table missing from this deployment must not abort the
			// cascade, and legacy reports nothing either.
		}
	}
}
