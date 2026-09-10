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
		for (int i = 0; i < employees; i++) {
			long id = 996100 + i;
			seedAsLegacyWould("INSERT INTO employees (id, company_id, branch_id, employee_code,"
					+ " first_name, last_name, phone, role, is_active,"
					+ " is_mobile_attendance_enabled, can_check_in_any_branch, join_request_status,"
					+ " token_version, created_at) VALUES (" + id + ", " + COMPANY + ", " + BRANCH
					+ ", '" + (7000 + i) + "', 'Budget', 'E" + i + "', '+2011002" + (50000 + i)
					+ "', 'employee', 1, 1, 0, 'accepted', 1, '2025-01-01 09:00:00')");
			for (String at : List.of(DAY + " 08:00:00", DAY + " 17:00:00")) {
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
	void theWorkPerPunchDoesNotGrowWithHowManyEmployeesArePaired() throws Exception {
		seedEmployeesWithOneDayEach(2);
		List<String> small = counter.measure(() -> service.pairCompany(COMPANY, "monday"));

		seed();
		seedEmployeesWithOneDayEach(8);
		List<String> large = counter.measure(() -> service.pairCompany(COMPANY, "monday"));

		// Four times the employees, four times the punches. Anything that is
		// per-punch scales with them; anything per-PASS must not.
		double ratio = (double) large.size() / small.size();
		assertThat(ratio)
				.as("a pass over 4x the punches issued %d statements against %d -- "
						+ "%.1fx. Per-punch work is expected; a jump well beyond 4x means "
						+ "something is being looked up repeatedly that could be looked up once",
						large.size(), small.size(), ratio)
				.isLessThan(6.0);
	}

	@Test
	void recordsTheCurrentPerPunchCostSoAChangeHasToBeDeliberate() throws Exception {
		seedEmployeesWithOneDayEach(4);

		List<String> issued = counter.measure(() -> service.pairCompany(COMPANY, "monday"));

		long perPunch = issued.size() / 8;
		assertThat(perPunch)
				.as("statements per punch, currently %d across %d total. This is a ratchet, "
						+ "not a target: if it drops, lower it; if it rises, that is a "
						+ "regression to explain. Busiest single statement repeated %d times.",
						perPunch, issued.size(), QueryCounter.busiestRepeat(issued))
				// Measured at 11 on 2026-09-10, then 9 after the runtime-offset
				// history moved to one read per pass and the branch policy
				// became a per-employee memo. Set at the measurement, with no
				// headroom: the next thing to change it should have to say so.
				.isLessThanOrEqualTo(9L);
	}
}
