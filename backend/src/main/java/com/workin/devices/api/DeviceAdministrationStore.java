package com.workin.devices.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.workin.legacy.LegacyJdbcValues;

/**
 * The platform administrator's reads across companies. Every other device
 * read is tenant-scoped by construction; these are not, which is why they live
 * apart and are reachable only through {@link DeviceAdministrationService}.
 * A {@code companyId} of null means every company -- the administrator's
 * unfiltered view -- and a value narrows it.
 */
@Component
public class DeviceAdministrationStore {

	private final JdbcTemplate jdbcTemplate;

	public DeviceAdministrationStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/**
	 * The last punch is the device's own clock, read as MAX over
	 * {@code device_punches_device_time_idx}: an index lookup per device, where
	 * the most recent {@code received_at} would read every punch the device ever
	 * sent.
	 */
	public List<Map<String, Object>> devices(Long companyId, int limit) {
		return devices(companyId, null, limit);
	}

	/** One device's row with its company and branch names. */
	public Map<String, Object> device(long deviceId) {
		List<Map<String, Object>> rows = devices(null, deviceId, 1);
		return rows.isEmpty() ? Map.of() : rows.get(0);
	}

	private List<Map<String, Object>> devices(Long companyId, Long deviceId, int limit) {
		List<Object> args = new ArrayList<>();
		StringBuilder sql = new StringBuilder("""
				SELECT d.id, d.company_id, c.company_name, d.branch_id, b.name AS branch_name, d.vendor,
				       d.serial_number, d.name, d.model, d.firmware, d.push_version, d.device_time_zone,
				       d.is_active, d.last_seen_at, d.last_handshake_at, d.last_seen_ip, d.created_at,
				       (SELECT MAX(p.punched_at_local) FROM device_punches p WHERE p.device_id = d.id) AS last_punch_at
				FROM attendance_devices d
				LEFT JOIN companies c ON c.id = d.company_id
				LEFT JOIN branches b ON b.id = d.branch_id""");
		sql.append(" WHERE 1 = 1");
		if (companyId != null) {
			sql.append(" AND d.company_id = ?");
			args.add(companyId);
		}
		if (deviceId != null) {
			sql.append(" AND d.id = ?");
			args.add(deviceId);
		}
		sql.append(" ORDER BY d.last_seen_at IS NULL, d.last_seen_at DESC, d.id DESC LIMIT ?");
		args.add(limit);
		return jdbcTemplate.query(sql.toString(), LegacyJdbcValues.rowMapper(), args.toArray());
	}

	public List<Map<String, Object>> sightings(int limit) {
		return jdbcTemplate.query("""
				SELECT serial_number, first_seen_at, last_seen_at, last_seen_ip, push_version, device_type, hit_count
				FROM unclaimed_device_sightings
				ORDER BY last_seen_at DESC LIMIT ?""", LegacyJdbcValues.rowMapper(), limit);
	}

	/** Newest received first. */
	public List<Map<String, Object>> punches(Long companyId, Long deviceId, int limit) {
		List<Object> args = new ArrayList<>();
		StringBuilder sql = new StringBuilder("""
				SELECT p.id, p.device_id, d.serial_number, d.name AS device_name, p.company_id, c.company_name,
				       p.pin, p.employee_id, TRIM(CONCAT_WS(' ', e.first_name, e.last_name)) AS employee_name,
				       p.punched_at_local, p.status_code, p.verify_code, p.received_at, p.processing_state,
				       p.review_flag, p.assignment_resolution, p.delivered_via
				FROM device_punches p
				JOIN attendance_devices d ON d.id = p.device_id
				LEFT JOIN companies c ON c.id = p.company_id
				LEFT JOIN employees e ON e.id = p.employee_id AND e.company_id = p.company_id
				WHERE 1 = 1""");
		if (companyId != null) {
			sql.append(" AND p.company_id = ?");
			args.add(companyId);
		}
		if (deviceId != null) {
			sql.append(" AND p.device_id = ?");
			args.add(deviceId);
		}
		sql.append(" ORDER BY p.id DESC LIMIT ?");
		args.add(limit);
		return jdbcTemplate.query(sql.toString(), LegacyJdbcValues.rowMapper(), args.toArray());
	}

	/** Counts by state and by how they arrived, for one device. */
	public List<Map<String, Object>> punchCounts(long deviceId) {
		return jdbcTemplate.query("""
				SELECT processing_state, delivered_via, COUNT(*) AS punches
				FROM device_punches WHERE device_id = ?
				GROUP BY processing_state, delivered_via
				ORDER BY processing_state, delivered_via""", LegacyJdbcValues.rowMapper(), deviceId);
	}

	public List<Map<String, Object>> malformed(long deviceId, int limit) {
		return jdbcTemplate.query("""
				SELECT id, received_at, raw_line FROM device_malformed_punches
				WHERE device_id = ? ORDER BY id DESC LIMIT ?""", LegacyJdbcValues.rowMapper(), deviceId, limit);
	}

	public boolean companyExists(long companyId) {
		Integer found = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM companies WHERE id = ?", Integer.class, companyId);
		return found != null && found > 0;
	}

	/** Active branches of every company, or of one, for the allocation form. */
	public List<Map<String, Object>> branches(Long companyId) {
		List<Object> args = new ArrayList<>();
		StringBuilder sql = new StringBuilder("""
				SELECT b.id, b.company_id, b.name, c.company_name
				FROM branches b JOIN companies c ON c.id = b.company_id
				WHERE b.is_active = 1""");
		if (companyId != null) {
			sql.append(" AND b.company_id = ?");
			args.add(companyId);
		}
		sql.append(" ORDER BY c.company_name, b.name LIMIT 2000");
		return jdbcTemplate.query(sql.toString(), LegacyJdbcValues.rowMapper(), args.toArray());
	}
}
