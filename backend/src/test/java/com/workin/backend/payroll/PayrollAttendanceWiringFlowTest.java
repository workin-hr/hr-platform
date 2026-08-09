package com.workin.backend.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.identity.AuthResponse;
import com.workin.backend.identity.RegisterCompanyRequest;

/**
 * Issue #71, end to end: a calculated batch must reflect what the
 * attendance table actually says, not an assumption that everyone was
 * present.
 *
 * <p>Before the wiring, every one of these cases produced the same
 * payslip — full pay, no absence — regardless of whether the employee
 * turned up. The period is a fixed historical month so nothing here
 * depends on the current date.
 */
class PayrollAttendanceWiringFlowTest extends AbstractIntegrationTest {

	private static final AtomicLong PHONE = new AtomicLong(7_950_000_000L);

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	@Qualifier("flywayDataSource")
	private DataSource flywayDataSource;

	private JdbcTemplate jdbc() {
		return new JdbcTemplate(flywayDataSource);
	}

	private static String uniquePhone() {
		return "+2" + PHONE.incrementAndGet();
	}

	private AuthResponse registerCompanyAdmin() {
		return restTemplate.postForEntity(
				"/api/auth/register",
				new RegisterCompanyRequest("Payroll Co", uniquePhone(), "correct horse battery staple"),
				AuthResponse.class).getBody();
	}

	private HttpHeaders bearer(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		headers.setContentType(MediaType.APPLICATION_JSON);
		return headers;
	}

	/** 09:00-17:00, resting Fridays and Saturdays, on a flat monthly salary. */
	private Long employeeOnSalary(Long companyId, String monthlySalary) {
		Long employeeId = jdbc().queryForObject(
				"INSERT INTO employees (company_id, first_name, last_name) VALUES (?, 'Pay', 'Emp') RETURNING id",
				Long.class, companyId);
		Long shiftId = jdbc().queryForObject(
				"INSERT INTO shifts (company_id, name, start_time, end_time, days_off) "
						+ "VALUES (?, 'Day', '09:00'::time, '17:00'::time, 'Friday,Saturday') RETURNING id",
				Long.class, companyId);
		jdbc().update(
				"INSERT INTO employee_shift_assignments (company_id, employee_id, shift_id, effective_from) "
						+ "VALUES (?, ?, ?, '2026-01-01'::date)",
				companyId, employeeId, shiftId);
		jdbc().update(
				"INSERT INTO salary_contracts (company_id, employee_id, salary_mode, basic_salary, effective_from) "
						+ "VALUES (?, ?, 'MONTHLY', ?::numeric, '2026-01-01'::date)",
				companyId, employeeId, monthlySalary);
		return employeeId;
	}

	/** A full 09:00-17:00 day. */
	private void punch(Long companyId, Long employeeId, String date) {
		jdbc().update(
				"INSERT INTO attendance (company_id, employee_id, check_in, check_out, method) "
						+ "VALUES (?, ?, ?::timestamptz, ?::timestamptz, 'app')",
				companyId, employeeId, date + "T09:00:00Z", date + "T17:00:00Z");
	}

	/** Every working day of March 2026 except the Fri/Sat rest days. */
	private void punchWholeMarch(Long companyId, Long employeeId) {
		for (int day = 2; day <= 31; day++) {
			java.time.LocalDate date = java.time.LocalDate.of(2026, 3, day);
			int dow = date.getDayOfWeek().getValue();
			if (dow == 5 || dow == 6) {
				continue;
			}
			punch(companyId, employeeId, date.toString());
		}
	}

	private Long createAndCalculateMarchBatch(AuthResponse admin) {
		ResponseEntity<String> created = restTemplate.exchange(
				"/api/tenant/payroll-batches", HttpMethod.POST,
				new HttpEntity<>("{\"month\":3,\"year\":2026}", bearer(admin.accessToken())), String.class);
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		Long batchId = jdbc().queryForObject(
				"SELECT id FROM payroll_batches WHERE company_id = ? AND month = 3 AND year = 2026",
				Long.class, admin.companyId());

		ResponseEntity<String> calculated = restTemplate.exchange(
				"/api/tenant/payroll-batches/" + batchId + "/calculate", HttpMethod.POST,
				new HttpEntity<>(bearer(admin.accessToken())), String.class);
		assertThat(calculated.getStatusCode().is2xxSuccessful()).isTrue();
		return batchId;
	}

	private record Slip(int daysPresent, int daysAbsent, BigDecimal netSalary, BigDecimal overtimeHours) {
	}

	private Slip payslip(Long batchId, Long employeeId) {
		return jdbc().queryForObject(
				"SELECT days_present, days_absent, net_salary, overtime_hours FROM payslips "
						+ "WHERE batch_id = ? AND employee_id = ?",
				(rs, n) -> new Slip(rs.getInt(1), rs.getInt(2), rs.getBigDecimal(3), rs.getBigDecimal(4)),
				batchId, employeeId);
	}

	@Test
	void anEmployeeWhoNeverTurnedUpIsNotPaidAFullSalary() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = employeeOnSalary(admin.companyId(), "3000");
		// No attendance rows at all.

		Slip slip = payslip(createAndCalculateMarchBatch(admin), employeeId);

		// Every scheduled working day is an absence, so pay collapses.
		// Before the wiring this produced days_absent = 0 and 3000.00.
		assertThat(slip.daysAbsent()).isGreaterThan(0);
		assertThat(slip.netSalary()).isLessThan(new BigDecimal("3000.00"));
	}

	@Test
	void anEmployeeWhoWorkedEveryScheduledDayKeepsFullPay() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = employeeOnSalary(admin.companyId(), "3000");
		punchWholeMarch(admin.companyId(), employeeId);

		Slip slip = payslip(createAndCalculateMarchBatch(admin), employeeId);

		assertThat(slip.daysAbsent()).isZero();
		assertThat(slip.netSalary()).isEqualByComparingTo("3000.00");
	}

	@Test
	void missingDaysCostTheGrossDayRateEach() {
		AuthResponse admin = registerCompanyAdmin();
		Long fullMonth = employeeOnSalary(admin.companyId(), "3000");
		Long shortMonth = employeeOnSalary(admin.companyId(), "3000");
		punchWholeMarch(admin.companyId(), fullMonth);
		punchWholeMarch(admin.companyId(), shortMonth);
		// Take two working days back off the second employee.
		jdbc().update(
				"DELETE FROM attendance WHERE employee_id = ? AND check_in::date IN "
						+ "('2026-03-02'::date, '2026-03-03'::date)",
				shortMonth);

		Long batchId = createAndCalculateMarchBatch(admin);

		Slip full = payslip(batchId, fullMonth);
		Slip shortSlip = payslip(batchId, shortMonth);
		assertThat(shortSlip.daysAbsent()).isEqualTo(full.daysAbsent() + 2);
		// gross 3000 / 30 = 100 a day, taken off the whole gross.
		assertThat(full.netSalary().subtract(shortSlip.netSalary())).isEqualByComparingTo("200.00");
	}

	@Test
	void extraHoursBeyondTheScheduledDayBecomePaidOvertime() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = employeeOnSalary(admin.companyId(), "3000");
		punchWholeMarch(admin.companyId(), employeeId);
		// Stretch one day from 8 hours to 12.
		jdbc().update(
				"UPDATE attendance SET check_out = '2026-03-02T21:00:00Z'::timestamptz "
						+ "WHERE employee_id = ? AND check_in::date = '2026-03-02'::date",
				employeeId);

		Slip slip = payslip(createAndCalculateMarchBatch(admin), employeeId);

		// The shift is 8h and the employee's resolved day is 8h, so 4 hours
		// are overtime -- at dayRate/8 x 1.25.
		assertThat(slip.overtimeHours()).isEqualByComparingTo("4.0");
		assertThat(slip.netSalary()).isGreaterThan(new BigDecimal("3000.00"));
	}

	@Test
	void anOfficialHolidayIsNotCountedAsAnAbsence() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = employeeOnSalary(admin.companyId(), "3000");
		punchWholeMarch(admin.companyId(), employeeId);
		// Remove a working day's punch, then declare it a holiday.
		jdbc().update(
				"DELETE FROM attendance WHERE employee_id = ? AND check_in::date = '2026-03-04'::date",
				employeeId);
		restTemplate.exchange("/api/tenant/official-holidays", HttpMethod.POST,
				new HttpEntity<>("{\"name\":\"National Day\",\"holidayDates\":[\"2026-03-04\"]}",
						bearer(admin.accessToken())),
				String.class);

		Slip slip = payslip(createAndCalculateMarchBatch(admin), employeeId);

		// The holiday is removed from what was due, so nothing is lost.
		assertThat(slip.daysAbsent()).isZero();
		assertThat(slip.netSalary()).isEqualByComparingTo("3000.00");
	}

}
