package com.workin.backend.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.identity.AuthResponse;
import com.workin.backend.identity.RegisterCompanyRequest;

/**
 * Regression test for the RLS policies added in
 * rls/V14__enable_payroll_group_row_level_security.sql. No JPA entities
 * exist yet for this schema-only slice, so this exercises the real
 * tables directly via JDBC -- fixtures are inserted through the
 * superuser (Flyway) DataSource, the same way other tests create
 * fixture rows for tables with no REST endpoint yet (e.g.
 * AuthFlowTest's multi-membership test).
 *
 * <p>Covers both company_id shapes introduced by V8-V13: direct
 * (payroll_batches) and denormalized-from-employee_id (payslips) --
 * not all 6 tables redundantly, since they all share one policy
 * pattern.
 */
class PayrollGroupRlsTest extends AbstractIntegrationTest {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	@Qualifier("flywayDataSource")
	private DataSource flywayDataSource;

	@Autowired
	private DataSource applicationDataSource;

	@Test
	@Transactional
	void payrollBatchesAndPayslipsAreCompanyScopedAndFailClosedWhenUnset() {
		AuthResponse companyA = register("Company A");
		AuthResponse companyB = register("Company B");

		JdbcTemplate superuser = new JdbcTemplate(flywayDataSource);
		Long employeeA = insertEmployee(superuser, companyA.companyId());
		Long employeeB = insertEmployee(superuser, companyB.companyId());
		Long batchA = insertPayrollBatch(superuser, companyA.companyId());
		Long batchB = insertPayrollBatch(superuser, companyB.companyId());
		insertPayslip(superuser, batchA, employeeA, companyA.companyId());
		insertPayslip(superuser, batchB, employeeB, companyB.companyId());

		JdbcTemplate appRuntime = new JdbcTemplate(applicationDataSource);

		// Fail-closed: no tenant context set yet in this transaction.
		assertThat(appRuntime.queryForList("SELECT id FROM payroll_batches")).isEmpty();
		assertThat(appRuntime.queryForList("SELECT id FROM payslips")).isEmpty();

		// Scope to company A only.
		appRuntime.queryForObject(
				"SELECT set_config('app.current_company_id', ?, true)", String.class, String.valueOf(companyA.companyId()));

		List<Long> visibleBatchIds = appRuntime.queryForList("SELECT id FROM payroll_batches", Long.class);
		assertThat(visibleBatchIds).containsExactly(batchA);

		List<Long> visiblePayslipEmployeeIds = appRuntime.queryForList("SELECT employee_id FROM payslips", Long.class);
		assertThat(visiblePayslipEmployeeIds).containsExactly(employeeA);
	}

	private AuthResponse register(String name) {
		String phone = "+2095" + System.nanoTime() % 100_000_000L;
		ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
				"/api/auth/register",
				new RegisterCompanyRequest(name, phone, "correct horse battery staple"),
				AuthResponse.class);
		return response.getBody();
	}

	private Long insertEmployee(JdbcTemplate superuser, Long companyId) {
		String phone = "+2094" + System.nanoTime() % 100_000_000L;
		superuser.update(
				"INSERT INTO employees (company_id, first_name, phone) VALUES (?, ?, ?)",
				companyId, "Test Employee", phone);
		return superuser.queryForObject(
				"SELECT id FROM employees WHERE phone = ?", Long.class, phone);
	}

	private Long insertPayrollBatch(JdbcTemplate superuser, Long companyId) {
		superuser.update(
				"INSERT INTO payroll_batches (company_id, month, year, period_from, period_to) "
						+ "VALUES (?, 1, 2026, '2026-01-01', '2026-01-31')",
				companyId);
		return superuser.queryForObject(
				"SELECT id FROM payroll_batches WHERE company_id = ? ORDER BY id DESC LIMIT 1", Long.class, companyId);
	}

	private void insertPayslip(JdbcTemplate superuser, Long batchId, Long employeeId, Long companyId) {
		superuser.update(
				"INSERT INTO payslips (batch_id, employee_id, company_id) VALUES (?, ?, ?)",
				batchId, employeeId, companyId);
	}

}
