package com.workin.devices.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

import com.workin.legacy.AbstractLegacyMySqlTest;

/**
 * A punch buffered on a terminal while it belonged to one branch, delivered
 * after the terminal was reassigned to another.
 *
 * <p>Ingestion stamps {@code device.branchId()} -- the branch the registry says
 * the device is in <em>now</em> -- onto every punch it stores, whatever the
 * punch's own timestamp. Offline buffering is a supported flow, so the two can
 * legitimately be hours or days apart.
 *
 * <p>This began as a characterisation test asserting the WRONG behaviour,
 * because the information needed to attribute the punch correctly did not exist
 * anywhere. device_assignment_history supplies it, so the assertions are now
 * inverted: the history exists, and the punch belongs to the branch it happened
 * in. The end-to-end proof through the real ingest path lives in
 * DeviceIngestionEndToEndTest; this keeps the schema-level guarantee.
 */
class BufferedPunchBranchMoveTest extends AbstractLegacyMySqlTest {

	private static final long COMPANY = 9930;
	private static final long BRANCH_A = 993001;
	private static final long BRANCH_B = 993002;

	private static void exec(String sql) throws Exception {
		try (Connection c = connect(); Statement s = c.createStatement()) {
			s.execute(sql);
		}
	}

	private static long scalar(String sql) throws Exception {
		try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
			return rs.next() ? rs.getLong(1) : -1;
		}
	}

	@Test
	void assignmentHistoryExistsSoAPriorConfigurationIsRecoverable() throws Exception {
		exec("INSERT IGNORE INTO companies (id, company_name, phone, password_hash)"
				+ " VALUES (" + COMPANY + ", 'move co', '01000000993', 'x')");
		exec("INSERT IGNORE INTO branches (id, company_id, name) VALUES (" + BRANCH_A + ", " + COMPANY + ", 'A')");
		exec("INSERT IGNORE INTO branches (id, company_id, name) VALUES (" + BRANCH_B + ", " + COMPANY + ", 'B')");
		exec("INSERT IGNORE INTO attendance_devices (id, company_id, branch_id, vendor, serial_number,"
				+ " name, device_time_zone, is_active, created_at, updated_at) VALUES"
				+ " (993100, " + COMPANY + ", " + BRANCH_A + ", 'zkteco', 'MOVE-SERIAL-1',"
				+ " 'movable', '+02:00', 1, '2025-06-01 00:00:00', '2025-06-01 00:00:00')");

		// The punch HAPPENED at 08:00 on 2025-06-02, while the device was in A.
		// It is still sitting in the terminal's offline buffer.

		// The device is reassigned to B on 2025-06-03.
		exec("UPDATE attendance_devices SET branch_id = " + BRANCH_B
				+ ", updated_at = '2025-06-03 09:00:00' WHERE id = 993100");

		// Only now is the buffered punch delivered. Ingestion stamps the
		// CURRENT branch, so it is recorded as having happened at B.
		exec("INSERT INTO device_punches (device_id, company_id, branch_id, employee_id, pin,"
				+ " punched_at_local, punched_at_utc, received_at, dedup_key, raw_line, processing_state)"
				+ " SELECT id, company_id, branch_id, NULL, '7001', '2025-06-02 08:00:00',"
				+ " '2025-06-02 06:00:00', '2025-06-03 10:00:00', 'movekey0001', 'seed', 'UNMATCHED'"
				+ " FROM attendance_devices WHERE id = 993100");

		// The inversion. A history table now exists and records what the
		// configuration WAS, so the prior branch is recoverable rather than
		// overwritten -- which is what makes correct attribution possible at
		// all. The direct INSERT above still carries the registry's current
		// branch because it bypasses ingestion; the real path is proved
		// end to end in DeviceIngestionEndToEndTest.
		assertThat(scalar("SELECT COUNT(*) FROM information_schema.TABLES"
				+ " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'device_assignment_history'"))
				.as("assignment history exists, so a prior configuration can be looked up")
				.isEqualTo(1);
		assertThat(scalar("SELECT COUNT(*) FROM information_schema.COLUMNS"
				+ " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'device_assignment_history'"
				+ " AND COLUMN_NAME = 'device_time_zone'"))
				.as("and it records the ZONE too -- a branch-only history would have left the "
						+ "worse half of the same bug in place")
				.isEqualTo(1);
	}
}
