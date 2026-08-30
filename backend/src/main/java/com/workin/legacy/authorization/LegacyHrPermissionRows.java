package com.workin.legacy.authorization;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyValues;
import com.workin.legacy.employees.LegacyEmployeeStore;

/**
 * {@code employee_row_attach_hr_permissions(&$row)}
 * ({@code helpers/hr_permissions.php:98-114}).
 *
 * <h2>Two sources, chosen by the row itself</h2>
 * <p>The helper looks at the row it was handed:
 *
 * <pre>
 * $has_joined = array_key_exists(Column::CAN_BRANCHES, $row);
 * $map = $has_joined ? hr_permissions_map_from_row($row)
 *                    : hr_permissions_for_employee((int) ($row[Column::ID] ?? 0));
 * </pre>
 *
 * <p>So a caller that already joined {@code hr_permissions} pays no second
 * query, and a caller that did not gets one. {@code profile/company.php} is the
 * first kind and {@code profile/employee.php} the second, which is why this
 * needs both branches rather than the join-only form
 * {@code LegacyHrEmployeeService} carries for its own module.
 *
 * <p>The probe column is {@code can_branches} specifically, not "any permission
 * column". A row that carried some other {@code can_*} key but not that one
 * would take the query path in PHP, and does here.
 *
 * <h2>What it does to the row</h2>
 * <p>Only for an {@code hr} or {@code manager} row -- every other role is left
 * exactly as selected, joined nulls included. For those two roles the
 * seventeen columns are replaced by a single {@code permissions} object, and a
 * missing or null column becomes {@code 0} there rather than null, because
 * {@code hr_permissions_default_map()} seeds every key with {@code 0} before
 * the row overwrites what it has.
 */
@Service
public class LegacyHrPermissionRows {

	private static final List<String> GRANULAR_ROLES = List.of("hr", "manager");
	private static final String PROBE_COLUMN = "can_branches";

	private final JdbcTemplate jdbcTemplate;

	public LegacyHrPermissionRows(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/**
	 * @param row mutated in place, as PHP's by-reference parameter is
	 */
	public void attach(Map<String, Object> row) {
		if (row == null || !GRANULAR_ROLES.contains(LegacyValues.toPhpString(row.get("role")))) {
			return;
		}
		Map<String, Object> map = row.containsKey(PROBE_COLUMN)
				? mapFromRow(row)
				: forEmployee(LegacyValues.toPhpLong(row.get("id")));
		for (String key : LegacyEmployeeStore.HR_PERMISSION_KEYS) {
			row.remove(key);
		}
		row.put("permissions", map);
	}

	/** {@code hr_permissions_for_employee()} ({@code hr_permissions.php:80-90}). */
	private Map<String, Object> forEmployee(long employeeId) {
		if (employeeId <= 0) {
			return defaultMap();
		}
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT * FROM hr_permissions WHERE employee_id = ? LIMIT 1",
				com.workin.legacy.LegacyJdbcValues.rowMapper(), employeeId);
		return rows.isEmpty() ? defaultMap() : mapFromRow(rows.get(0));
	}

	/** {@code hr_permissions_map_from_row()}: default 0, overwritten by present keys. */
	private static Map<String, Object> mapFromRow(Map<String, Object> row) {
		Map<String, Object> map = defaultMap();
		for (String key : LegacyEmployeeStore.HR_PERMISSION_KEYS) {
			if (row.containsKey(key)) {
				map.put(key, LegacyValues.toPhpLong(row.get(key)));
			}
		}
		return map;
	}

	private static Map<String, Object> defaultMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		for (String key : LegacyEmployeeStore.HR_PERMISSION_KEYS) {
			map.put(key, 0L);
		}
		return map;
	}
}
