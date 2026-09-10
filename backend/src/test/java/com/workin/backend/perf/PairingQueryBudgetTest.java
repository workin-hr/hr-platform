package com.workin.backend.perf;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.workin.legacy.AbstractLegacyMySqlTest;
import com.workin.legacy.LegacyClock;
import com.workin.legacy.attendance.LegacyWeeklyOffDays;
import com.workin.legacy.attendance.calendar.LegacyAttendanceCalendar;
import com.workin.legacy.attendance.pairing.PunchPairingService;
import com.workin.legacy.attendance.pairing.PunchPairingStore;
import com.workin.legacy.attendance.session.LegacyAttendanceSessions;

/**
 * What a pairing pass costs in round trips, and how that cost scales.
 *
 * <p>A fixed budget would be brittle and would say nothing useful: the number
 * that matters is whether the work per punch is constant. A pass over one
 * employee's day and a pass over the same day for many employees should differ
 * by the punches themselves, not by a lookup repeated for each one.
 *
 * <p>This exists because the shape has already bitten this repository once, in
 * a payslip enrichment loop that drove a per-employee, per-day query and turned
 * one page into hundreds of round trips. Nothing was watching for the next one.
 */
class PairingQueryBudgetTest extends AbstractLegacyMySqlTest {

	private static final long COMPANY = 9960;
	private static final long BRANCH = 996001;
	private static final long DEVICE = 996500;
	private static final String DAY = "2025-06-02";

	private QueryCounter counter;
	private PunchPairingService service;

	private static void exec(String sql) throws Exception {
		try (Connection c = connect(); Statement s = c.createStatement()) {
			s.execute(sql);
		}
	}

	@BeforeEach
	void seed() throws Exception {
		exec("DELETE FROM device_punches WHERE company_id = " + COMPANY);
		exec("DELETE FROM attendance WHERE employee_id IN"
				+ " (SELECT id FROM employees WHERE company_id = " + COMPANY + ")");
		exec("DELETE FROM employees WHERE company_id = " + COMPANY);
		exec("DELETE FROM branches WHERE id = " + BRANCH);
		exec("DELETE FROM companies WHERE id = " + COMPANY);
		seedAsLegacyWould(
				"INSERT INTO companies (id, company_name, phone, password_hash, status,"
						+ " otp_verified, profile_completed, created_at) VALUES ("
						+ COMPANY + ", 'Budget Co', '+201100249960', 'x', 'active', 1, 1,"
						+ " '2025-01-01 09:00:00')",
				"INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES ("
						+ BRANCH + ", " + COMPANY + ", 'HQ', 1, '2025-01-01 09:00:00')");
		exec("DELETE FROM attendance_devices WHERE id = " + DEVICE);
		seedAsLegacyWould("INSERT INTO attendance_devices (id, company_id, branch_id, vendor,"
				+ " serial_number, name, device_time_zone, is_active, created_at, updated_at)"
				+ " VALUES (" + DEVICE + ", " + COMPANY + ", " + BRANCH + ", 'ZKTECO',"
				+ " 'BUDGET-1', 'Budget Gate', '+02:00', 1, '2025-01-01 09:00:00',"
				+ " '2025-01-01 09:00:00')");

		com.workin.legacy.runtime.LegacyRuntimeOffsetHistoryTest.installHooks();
		exec("DELETE FROM legacy_runtime_offset_history");
		seedAsLegacyWould("INSERT INTO legacy_runtime_offset_history"
				+ " (effective_from_utc, offset_seconds) VALUES ('2000-01-01 00:00:00', 7200)");

		this.counter = new QueryCounter();
		DataSource raw = new DriverManagerDataSource(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
		DataSource counted = counter.wrap(raw);
		LegacyClock clock = new LegacyClock(counted);
		LegacyAttendanceCalendar calendar =
				new LegacyAttendanceCalendar(counted, new LegacyWeeklyOffDays(counted));
		this.service = new PunchPairingService(new PunchPairingStore(counted),
				new LegacyAttendanceSessions(counted, calendar, clock), counted, clock, 500);
	}

	private void seedEmployeesWithOneDayEach(int employees) throws Exception {
		seedEmployeesWithPunches(employees, 2);
	}

	/** {@code employees} people, {@code punchesEach} punches apiece on one day. */
	private void seedEmployeesWithPunches(int employees, int punchesEach) throws Exception {
		for (int i = 0; i < employees; i++) {
			long id = 996100 + i;
			seedAsLegacyWould("INSERT INTO employees (id, company_id, branch_id, employee_code,"
					+ " first_name, last_name, phone, role, is_active,"
					+ " is_mobile_attendance_enabled, can_check_in_any_branch, join_request_status,"
					+ " token_version, created_at) VALUES (" + id + ", " + COMPANY + ", " + BRANCH
					+ ", '" + (7000 + i) + "', 'Budget', 'E" + i + "', '+2011002" + (50000 + i)
					+ "', 'employee', 1, 1, 0, 'accepted', 1, '2025-01-01 09:00:00')");
			List<String> times = new java.util.ArrayList<>();
			for (int p = 0; p < punchesEach; p++) {
				times.add(String.format("%s %02d:00:00", DAY, 8 + p));
			}
			for (String at : times) {
				String key = String.format("%064x", (at + id).hashCode() & 0xffffffffL);
				seedAsLegacyWould("INSERT INTO device_punches (device_id, company_id, branch_id,"
						+ " employee_id, pin, punched_at_local, punched_at_utc, received_at,"
						+ " dedup_key, raw_line, processing_state) VALUES (" + DEVICE + ", " + COMPANY + ", "
						+ BRANCH + ", " + id + ", '" + (7000 + i) + "', '" + at + "', '"
						+ at.replace(" 08:", " 06:").replace(" 17:", " 15:") + "', '" + at + "', '"
						+ key + "', 'seed', 'RECEIVED')");
			}
		}
	}

	@Test
	void perEmployeeWorkDoesNotRideAlongInsideThePerPunchLoop() throws Exception {
		// SAME punch count, different employee count. The previous version of
		// this test used 2 punches per employee in both arms, so employees rose
		// in step with punches and per-employee work was algebraically
		// indistinguishable from per-punch work: for any cost of the form
		// P + c*n the assertion reduced to something true for all P and c, and
		// nothing short of superlinear growth could fail it.
		//
		// Sixteen punches either way. If a lookup happens once per EMPLOYEE
		// inside the loop, the 8-employee arm pays it four times as often as
		// the 2-employee arm and the difference has nowhere to hide.
		seedEmployeesWithPunches(2, 8);
		int fewEmployees = counter.measure(() -> service.pairCompany(COMPANY, "monday")).size();

		seed();
		seedEmployeesWithPunches(8, 2);
		int manyEmployees = counter.measure(() -> service.pairCompany(COMPANY, "monday")).size();

		// The PER-EMPLOYEE cost, isolated. Some of it is legitimate and expected:
		// the branch policy is a per-employee fact and the memo's whole purpose
		// is to pay for it once each. What must not grow is that constant --
		// measured at 3 statements per employee (133 vs 115 across 6 extra),
		// which is one memoised policy read plus its share of the pass.
		//
		// The two assertions in this class catch DIFFERENT regressions, and it is
		// worth being exact because an earlier version of this comment was not.
		// Removing the branch-policy memo makes that read per PUNCH, which
		// raises both arms and trips the RATCHET below (73 against 69) while
		// leaving this figure flat -- so the ratchet guards per-punch cost and
		// this guards per-employee cost. Neither substitutes for the other.
		double perEmployee = (manyEmployees - fewEmployees) / 6.0;
		assertThat(perEmployee)
				.as("16 punches across 8 employees issued %d statements and across 2 employees "
						+ "%d -- %.1f per extra employee. A rise means something is looked up "
						+ "per employee that is not already memoised for the pass (D-114).",
						manyEmployees, fewEmployees, perEmployee)
				.isLessThanOrEqualTo(4.0);
	}

	@Test
	void recordsTheCurrentPerPunchCostSoAChangeHasToBeDeliberate() throws Exception {
		seedEmployeesWithOneDayEach(4);

		List<String> issued = counter.measure(() -> service.pairCompany(COMPANY, "monday"));

		// The TOTAL, not a per-punch integer division. `issued.size() / 8`
		// quantised: at 8 per punch anything from 64 to 71 rounded to the same
		// number, so up to seven extra statements per pass could land while the
		// assertion's own comment claimed "no headroom". It now asserts the
		// figure it actually measured.
		assertThat(issued.size())
				.as("statements for 8 punches, currently %d. A ratchet, not a target: if it "
						+ "drops, lower it; if it rises, that is a regression to explain. "
						+ "Busiest single statement repeated %d times.",
						issued.size(), QueryCounter.busiestRepeat(issued))
				.isLessThanOrEqualTo(69);
	}
}
