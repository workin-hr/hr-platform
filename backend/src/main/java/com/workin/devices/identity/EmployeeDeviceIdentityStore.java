package com.workin.devices.identity;

import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.workin.devices.DeviceAttendanceEvent;
import com.workin.legacy.LegacyJdbcValues;

/**
 * Device PIN to employee, per company (Q1, D-164).
 *
 * <p>Resolution order: an explicit {@code employee_device_identities} row,
 * then {@code employees.employee_code} equal to the PIN -- the convention the
 * Excel punch-log import already relies on, so a company that never binds a
 * PIN gets the behaviour it has today. A code shared by two employees of one
 * company is ambiguous and resolves to nobody rather than to the lower id.
 *
 * <p><b>Two deliberate differences from that import.</b> The fallback here
 * requires {@code is_active = 1}: a badge is a physical object that outlives
 * employment, so a punch on a departed employee's PIN should reach an operator
 * as {@code UNMATCHED} rather than be attributed to them. And the comparison is
 * the column's own {@code utf8mb4_unicode_ci} collation, so it ignores case and
 * trailing spaces -- {@code '1001 '} in a spreadsheet-imported row does match
 * PIN {@code 1001}. That is the storage contract rather than a choice made
 * here; an explicit binding is the way to be exact.
 *
 * <p>Resolution is per <em>batch</em>, not per punch, and that is not a
 * micro-optimisation: a terminal that was offline delivers its whole buffer
 * in one upload, and every statement on this datasource costs a connection
 * checkout that {@code LegacySessionDataSource} prefixes with a time-zone
 * round trip (D-099). Per punch, a 500-record reconnect would have paid a
 * thousand of them; per batch it pays two per chunk of distinct PINs.
 */
@Component
public class EmployeeDeviceIdentityStore {

	/**
	 * PINs per {@code IN} list. Bounded so a single upload cannot build an
	 * arbitrarily large statement out of device-supplied values.
	 */
	private static final int CHUNK = 200;

	private final JdbcTemplate jdbcTemplate;

	public EmployeeDeviceIdentityStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/**
	 * @return the PINs that resolve, mapped to their employee; a PIN that
	 *         resolves to nobody is simply absent, which is what makes the
	 *         punch {@code UNMATCHED} rather than dropped
	 */
	public Map<String, Long> resolveEmployeeIds(long companyId, Collection<String> pins) {
		Set<String> distinct = pins.stream().filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
		Map<String, Long> resolved = new HashMap<>();
		if (distinct.isEmpty()) {
			return resolved;
		}
		for (List<String> chunk : chunks(distinct)) {
			// Joined to employees, not read alone: an explicit binding must stop
			// resolving when its employee is deactivated, exactly as the
			// employee_code fallback below does. Otherwise a departed person's
			// badge would keep producing punches attributed to them, and only
			// the unbound half of the company would behave correctly.
			jdbcTemplate.query(
					"SELECT i.pin AS pin, i.employee_id AS employee_id FROM employee_device_identities i"
							+ " INNER JOIN employees e ON e.id = i.employee_id AND e.company_id = i.company_id"
							+ " WHERE i.company_id = ? AND e.is_active = 1 AND i.pin IN ("
							+ placeholders(chunk.size()) + ")",
					(ResultSet rs) -> {
						while (rs.next()) {
							String pin = normalized(rs.getString("pin"));
							if (pin != null) {
								resolved.put(pin, rs.getLong("employee_id"));
							}
						}
						return null;
					},
					arguments(companyId, chunk));
		}

		List<String> unbound = distinct.stream().filter(pin -> !resolved.containsKey(pin)).toList();
		for (List<String> chunk : chunks(unbound)) {
			Map<String, Long> byCode = new HashMap<>();
			Set<String> ambiguous = new LinkedHashSet<>();
			jdbcTemplate.query(
					"SELECT employee_code, id FROM employees WHERE company_id = ? AND is_active = 1 "
							+ "AND employee_code IN (" + placeholders(chunk.size()) + ")",
					(ResultSet rs) -> {
						while (rs.next()) {
							// The column's collation matched this row to the PIN
							// ignoring case and trailing spaces, but JDBC returns
							// the stored text. Keying the map by that raw value
							// would make the caller's later lookup of the PIN miss
							// -- the punch would be UNMATCHED for a code that did
							// match. Normalise back to the queried form.
							String code = normalized(rs.getString("employee_code"));
							if (code == null) {
								continue;
							}
							// Two employees sharing a code cannot be told apart,
							// so neither is chosen -- the punches land UNMATCHED
							// and an operator binds the PIN explicitly.
							if (byCode.put(code, rs.getLong("id")) != null) {
								ambiguous.add(code);
							}
						}
						return null;
					},
					arguments(companyId, chunk));
			byCode.keySet().removeAll(ambiguous);
			resolved.putAll(byCode);
		}
		return resolved;
	}

	public boolean employeeBelongsToCompany(long companyId, long employeeId) {
		Long count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM employees WHERE company_id = ? AND id = ?", Long.class, companyId, employeeId);
		return count != null && count > 0;
	}

	public enum BindOutcome { BOUND, PIN_TAKEN, EMPLOYEE_ALREADY_BOUND }

	/**
	 * Binds a PIN to an employee, replacing that employee's previous PIN.
	 * Two unique keys guard the two mistakes: a PIN already on someone else,
	 * and -- once the employee's own row is updated in place -- an insert
	 * that can then only collide on the PIN.
	 */
	public BindOutcome bind(long companyId, long employeeId, String pin, String cardNo, LocalDateTime now) {
		List<Long> pinOwner = jdbcTemplate.queryForList(
				"SELECT employee_id FROM employee_device_identities WHERE company_id = ? AND pin = ?",
				Long.class, companyId, pin);
		if (!pinOwner.isEmpty() && pinOwner.get(0) != employeeId) {
			return BindOutcome.PIN_TAKEN;
		}
		String stamp = DeviceAttendanceEvent.SQL_DATE_TIME.format(now);
		// Both statements sit inside the catch, not only the INSERT: the
		// pre-check above is not a lock, so another admin can take this PIN
		// between it and either write. The UPDATE violates the PIN key just as
		// the INSERT does, and an escaping DuplicateKeyException would be a 500
		// where the caller should see the 409 this method exists to report.
		try {
			int updated = jdbcTemplate.update("""
					UPDATE employee_device_identities SET pin = ?, card_no = ?, source = 'MANUAL', updated_at = ?
					WHERE company_id = ? AND employee_id = ?""", pin, cardNo, stamp, companyId, employeeId);
			if (updated > 0) {
				return BindOutcome.BOUND;
			}
			jdbcTemplate.update("""
					INSERT INTO employee_device_identities
					  (company_id, employee_id, pin, card_no, source, created_at, updated_at)
					VALUES (?, ?, ?, ?, 'MANUAL', ?, ?)""", companyId, employeeId, pin, cardNo, stamp, stamp);
		} catch (DuplicateKeyException ex) {
			// Whichever unique key the concurrent writer took: the PIN, or this
			// employee's own single-identity row.
			return pinOwner.isEmpty() ? BindOutcome.PIN_TAKEN : BindOutcome.EMPLOYEE_ALREADY_BOUND;
		}
		return BindOutcome.BOUND;
	}

	public List<Map<String, Object>> listForCompany(long companyId) {
		return jdbcTemplate.query("""
				SELECT i.employee_id, i.pin, i.card_no, i.source, i.updated_at,
				       TRIM(CONCAT(COALESCE(e.first_name, ''), ' ', COALESCE(e.last_name, ''))) AS employee_name
				FROM employee_device_identities i
				LEFT JOIN employees e ON e.id = i.employee_id AND e.company_id = i.company_id
				WHERE i.company_id = ?
				ORDER BY i.employee_id""", LegacyJdbcValues.rowMapper(), companyId);
	}

	/** The comparison the database made, reproduced in Java: trim, and PINs are digits so case cannot differ. */
	private static String normalized(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.strip();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static List<List<String>> chunks(Collection<String> values) {
		List<String> all = List.copyOf(values);
		List<List<String>> chunks = new ArrayList<>();
		for (int start = 0; start < all.size(); start += CHUNK) {
			chunks.add(all.subList(start, Math.min(start + CHUNK, all.size())));
		}
		return chunks;
	}

	private static String placeholders(int count) {
		return String.join(",", java.util.Collections.nCopies(count, "?"));
	}

	private static Object[] arguments(long companyId, List<String> chunk) {
		Object[] args = new Object[chunk.size() + 1];
		args[0] = companyId;
		for (int index = 0; index < chunk.size(); index++) {
			args[index + 1] = chunk.get(index);
		}
		return args;
	}

}
