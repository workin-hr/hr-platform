package com.workin.legacy.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.workin.legacy.AbstractLegacyMySqlTest;
import com.workin.legacy.wire.LegacyMessages;

/**
 * The device-table cleanup inside a company hard-delete.
 *
 * <p>The cascade tolerates missing optional tables on purpose -- a partially
 * provisioned deployment must still be able to delete a company. It did that by
 * swallowing every {@code RuntimeException}, which cannot tell "this table is
 * not deployed here" from "this delete failed". The second case matters far
 * more than it looks: {@code attendance_devices} rows are what make a serial
 * recognised, so one surviving row keeps a terminal ingesting punches against a
 * company that no longer exists.
 */
class LegacyCompanyDeleteDeviceCleanupTest extends AbstractLegacyMySqlTest {

	private static final long COMPANY = 9910;

	@AfterEach
	void dropTrigger() throws Exception {
		exec("DROP TRIGGER IF EXISTS block_device_delete");
	}

	private static void exec(String sql) throws Exception {
		try (Connection c = connect(); Statement s = c.createStatement()) {
			s.execute(sql);
		}
	}

	private static int count(String sql) throws Exception {
		try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
			return rs.next() ? rs.getInt(1) : -1;
		}
	}

	private LegacyCompanyDelete deleteService() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
		return new LegacyCompanyDelete(dataSource, new LegacyMessages());
	}

	private void seedCompanyWithDevice() throws Exception {
		exec("INSERT IGNORE INTO companies (id, company_name, phone, password_hash)"
				+ " VALUES (" + COMPANY + ", 'probe co', '01000000991', 'x')");
		exec("INSERT IGNORE INTO branches (id, company_id, name)"
				+ " VALUES (991000, " + COMPANY + ", 'probe branch')");
		exec("INSERT IGNORE INTO attendance_devices"
				+ " (id, company_id, branch_id, vendor, serial_number, name, device_time_zone, is_active)"
				+ " VALUES (991001, " + COMPANY + ", 991000, 'zkteco', 'PROBE-SERIAL-1',"
				+ " 'probe device', 'Africa/Cairo', 1)");
	}

	@Test
	void aFailedDeleteFromAPresentDeviceTableAbortsTheCascade() throws Exception {
		seedCompanyWithDevice();
		// Force the delete to fail for a reason that is NOT "table absent" --
		// the shape of a missing DELETE grant or a lock timeout in production.
		exec("CREATE TRIGGER block_device_delete BEFORE DELETE ON attendance_devices"
				+ " FOR EACH ROW SIGNAL SQLSTATE '45000'"
				+ " SET MESSAGE_TEXT = 'delete refused'");

		assertThatThrownBy(() -> deleteService().cascadeDelete(COMPANY, "en"))
				.as("a failed delete from a PRESENT device table must abort, not be swallowed")
				.isInstanceOf(RuntimeException.class);

		// The whole point: the tenant must still be there, so the operator sees
		// a failure and retries -- rather than a company that is gone while its
		// serial stays live.
		assertThat(count("SELECT COUNT(*) FROM companies WHERE id = " + COMPANY))
				.as("the company must survive a cleanup failure")
				.isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM attendance_devices WHERE company_id = " + COMPANY))
				.as("and its device row with it")
				.isEqualTo(1);
	}

	@Test
	void aGenuinelyAbsentOptionalTableIsStillTolerated() throws Exception {
		seedCompanyWithDevice();
		exec("DROP TABLE IF EXISTS device_operation_logs");
		try {
			deleteService().cascadeDelete(COMPANY, "en");
			assertThat(count("SELECT COUNT(*) FROM companies WHERE id = " + COMPANY))
					.as("a partially provisioned deployment must still delete a company")
					.isZero();
		} finally {
			exec("""
					CREATE TABLE IF NOT EXISTS device_operation_logs (
					    id BIGINT AUTO_INCREMENT PRIMARY KEY,
					    device_id BIGINT NOT NULL,
					    company_id INT UNSIGNED NOT NULL,
					    received_at DATETIME NOT NULL,
					    raw_line VARCHAR(512) NOT NULL,
					    dedup_key CHAR(64) NOT NULL UNIQUE)""");
		}
	}
}
