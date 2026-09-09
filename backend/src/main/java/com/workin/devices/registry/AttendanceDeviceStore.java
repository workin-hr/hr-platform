package com.workin.devices.registry;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.workin.devices.assignment.DeviceAssignmentHistoryStore;
import com.workin.legacy.LegacyClock;
import javax.sql.DataSource;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import com.workin.devices.DeviceAttendanceEvent;
import com.workin.legacy.LegacyJdbcValues;

/**
 * The device registry. Every read that answers a tenant carries
 * {@code company_id}; the one read that does not -- {@link #findBySerial} --
 * is the trust-boundary lookup the receiver uses to <em>derive</em> the
 * tenant from a serial, and its result is what makes every later write
 * tenant-scoped.
 */
@Component
public class AttendanceDeviceStore {

	private static final String COLUMNS = """
			id, company_id, branch_id, vendor, serial_number, name, model, firmware, push_version,
			device_time_zone, is_active, last_seen_at, last_handshake_at, last_attlog_stamp, last_seen_ip,
			created_at, updated_at""";

	private final JdbcTemplate jdbcTemplate;

	private final TransactionTemplate transactions;
	private final DeviceAssignmentHistoryStore history;
	private final LegacyClock clock;

	public AttendanceDeviceStore(DataSource legacyDataSource, DeviceAssignmentHistoryStore history,
			LegacyClock clock) {
		this.transactions = new TransactionTemplate(new DataSourceTransactionManager(legacyDataSource));
		this.history = history;
		this.clock = clock;
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	public Optional<AttendanceDevice> findBySerial(String serialNumber) {
		return first(jdbcTemplate.query(
				"SELECT " + COLUMNS + " FROM attendance_devices WHERE serial_number = ?",
				LegacyJdbcValues.rowMapper(), serialNumber));
	}

	public Optional<AttendanceDevice> findForCompany(long companyId, long id) {
		return first(jdbcTemplate.query(
				"SELECT " + COLUMNS + " FROM attendance_devices WHERE company_id = ? AND id = ?",
				LegacyJdbcValues.rowMapper(), companyId, id));
	}

	public List<AttendanceDevice> listForCompany(long companyId) {
		return jdbcTemplate.query(
				"SELECT " + COLUMNS + " FROM attendance_devices WHERE company_id = ? ORDER BY id",
				LegacyJdbcValues.rowMapper(), companyId).stream().map(AttendanceDeviceStore::toDevice).toList();
	}

	/** {@code branches.company_id} for the id, or null -- the caller decides what a foreign branch means. */
	public Long branchCompanyId(long branchId) {
		List<Long> values = jdbcTemplate.queryForList(
				"SELECT company_id FROM branches WHERE id = ?", Long.class, branchId);
		return values.isEmpty() ? null : values.get(0);
	}

	/**
	 * Registers a serial for a company/branch.
	 *
	 * @return the new id, or empty when the serial is already registered --
	 *         to anyone; the unique key is global, because a serial is one
	 *         physical terminal
	 */
	public Optional<Long> claim(
			long companyId, long branchId, String vendor, String serialNumber, String name,
			String deviceTimeZone, Long registeredByEmployeeId, LocalDateTime now) {
		String stamp = DeviceAttendanceEvent.SQL_DATE_TIME.format(now);
		KeyHolder keys = new GeneratedKeyHolder();
		try {
			jdbcTemplate.update(connection -> {
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO attendance_devices
						  (company_id, branch_id, vendor, serial_number, name, device_time_zone, is_active,
						   registered_by_employee_id, created_at, updated_at)
						VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS);
				statement.setLong(1, companyId);
				statement.setLong(2, branchId);
				statement.setString(3, vendor);
				statement.setString(4, serialNumber);
				statement.setString(5, name);
				statement.setString(6, deviceTimeZone);
				if (registeredByEmployeeId == null) {
					statement.setNull(7, java.sql.Types.BIGINT);
				} else {
					statement.setLong(7, registeredByEmployeeId);
				}
				statement.setString(8, stamp);
				statement.setString(9, stamp);
				return statement;
			}, keys);
		} catch (DuplicateKeyException ex) {
			return Optional.empty();
		}
		Number id = keys.getKey();
		return id == null ? Optional.empty() : Optional.of(id.longValue());
	}

	/**
	 * Claim, and the device's first history row, in one transaction.
	 *
	 * <p>Without the initial row the timeline has no beginning, and every punch
	 * from this device would resolve as INFERRED_EARLIEST -- correct in spirit,
	 * useless in practice, and it would stop pairing entirely.
	 */
	public Optional<Long> claimWithHistory(
			long companyId, long branchId, String vendor, String serialNumber, String name,
			String deviceTimeZone, Long registeredByEmployeeId, LocalDateTime now) {
		return transactions.execute(status -> {
			Optional<Long> claimed = claim(companyId, branchId, vendor, serialNumber, name,
					deviceTimeZone, registeredByEmployeeId, now);
			claimed.ifPresent(id -> history.append(id, companyId, branchId, deviceTimeZone,
					DeviceAssignmentHistoryStore.toUtc(now, clock.offset()), now));
			return claimed;
		});
	}

	/**
	 * A configuration change, and its history row, in one transaction.
	 *
	 * <p>The row is locked before anything is decided, so two concurrent
	 * changes serialise rather than interleaving into a registry that disagrees
	 * with the newest history row.
	 *
	 * <p>What counts as a change is the ACTUAL value, not the presence of a
	 * field in the request: re-sending the same branch appends nothing. A
	 * branch and zone changing together append exactly ONE row carrying the new
	 * combined configuration -- two rows would imply a moment when only half
	 * the change had happened, which never existed.
	 */
	public void update(
			long companyId, long id, String name, Long branchId, String deviceTimeZone, Boolean active,
			LocalDateTime now) {
		transactions.executeWithoutResult(status -> {
			List<Map<String, Object>> current = jdbcTemplate.queryForList(
					"SELECT branch_id, device_time_zone FROM attendance_devices"
							+ " WHERE company_id = ? AND id = ? FOR UPDATE",
					companyId, id);
			if (current.isEmpty()) {
				return;
			}
			long currentBranch = ((Number) current.get(0).get("branch_id")).longValue();
			String currentZone = (String) current.get(0).get("device_time_zone");

			jdbcTemplate.update("""
					UPDATE attendance_devices
					SET name = COALESCE(?, name), branch_id = COALESCE(?, branch_id),
					    device_time_zone = COALESCE(?, device_time_zone), is_active = COALESCE(?, is_active),
					    updated_at = ?
					WHERE company_id = ? AND id = ?""",
					name, branchId, deviceTimeZone, active == null ? null : (active ? 1 : 0),
					DeviceAttendanceEvent.SQL_DATE_TIME.format(now), companyId, id);

			long newBranch = branchId == null ? currentBranch : branchId;
			String newZone = deviceTimeZone == null ? currentZone : deviceTimeZone;
			if (newBranch != currentBranch || !newZone.equals(currentZone)) {
				history.append(id, companyId, newBranch, newZone,
						DeviceAssignmentHistoryStore.toUtc(now, clock.offset()), now);
			}
		});
	}

	/** Every request from a claimed device: liveness, and the address it came from. */
	public void touchSeen(long id, String ip, LocalDateTime now) {
		jdbcTemplate.update(
				"UPDATE attendance_devices SET last_seen_at = ?, last_seen_ip = ? WHERE id = ?",
				DeviceAttendanceEvent.SQL_DATE_TIME.format(now), ip, id);
	}

	/**
	 * Liveness and the handshake in one statement. Every connection checkout
	 * on this datasource costs an extra round trip -- LegacySessionDataSource
	 * resolves and applies the session time zone on each one (D-099) -- and a
	 * handshake happens on every device boot and reconnect, so the two
	 * updates this replaces were two checkouts for one row.
	 *
	 * @param pushVersion null leaves the stored value alone, which is how a
	 *        deactivated device is handshaken without learning anything new
	 *        from it
	 */
	public void recordHandshake(long id, String ip, String pushVersion, LocalDateTime now) {
		String stamp = DeviceAttendanceEvent.SQL_DATE_TIME.format(now);
		jdbcTemplate.update("""
				UPDATE attendance_devices
				SET last_seen_at = ?, last_seen_ip = ?, last_handshake_at = ?,
				    push_version = COALESCE(?, push_version)
				WHERE id = ?""", stamp, ip, stamp, pushVersion, id);
	}

	/** What the device reports about itself in a {@code table=options} upload. */
	public void recordSelfDescription(long id, String model, String firmware, String pushVersion) {
		jdbcTemplate.update("""
				UPDATE attendance_devices
				SET model = COALESCE(?, model), firmware = COALESCE(?, firmware),
				    push_version = COALESCE(?, push_version)
				WHERE id = ?""", model, firmware, pushVersion, id);
	}

	public void recordAttlogStamp(long id, String stamp) {
		jdbcTemplate.update(
				"UPDATE attendance_devices SET last_attlog_stamp = ? WHERE id = ?", stamp, id);
	}

	private static Optional<AttendanceDevice> first(List<Map<String, Object>> rows) {
		return rows.isEmpty() ? Optional.empty() : Optional.of(toDevice(rows.get(0)));
	}

	private static AttendanceDevice toDevice(Map<String, Object> row) {
		return new AttendanceDevice(
				asLong(row.get("id")), asLong(row.get("company_id")), asLong(row.get("branch_id")),
				asText(row.get("vendor")), asText(row.get("serial_number")), asText(row.get("name")),
				asText(row.get("model")), asText(row.get("firmware")), asText(row.get("push_version")),
				asText(row.get("device_time_zone")), asLong(row.get("is_active")) != 0L,
				asText(row.get("last_seen_at")), asText(row.get("last_handshake_at")),
				asText(row.get("last_attlog_stamp")), asText(row.get("last_seen_ip")),
				asText(row.get("created_at")), asText(row.get("updated_at")));
	}

	static long asLong(Object value) {
		return value == null ? 0L : Long.parseLong(String.valueOf(value).trim());
	}

	static String asText(Object value) {
		return value == null ? null : String.valueOf(value);
	}
}
