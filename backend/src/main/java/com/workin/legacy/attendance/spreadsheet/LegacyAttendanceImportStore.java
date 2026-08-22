package com.workin.legacy.attendance.spreadsheet;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import com.workin.legacy.LegacyPdoException;

/**
 * Every statement {@code import_excel.php} issues, on <b>one</b> connection.
 *
 * <h2>Why a connection rather than a {@code JdbcTemplate}</h2>
 * <p>PHP holds a single PDO singleton: {@code getDB()} returns it,
 * {@code db_pdo()} caches the same instance, and {@code $pdo->beginTransaction()}
 * in the endpoint therefore encloses <em>everything</em> the helper does --
 * the duplicate probes and the attendance inserts, but also the employee
 * lookups, the {@code employee_code} sync and the employee/shift-assignment
 * inserts that a {@code mappings} entry can trigger. Those last three go
 * through {@code db_value()}, {@code get_one()} and {@code execute_query()},
 * which look like independent helpers and are not.
 *
 * <p>Reproducing that with a shared {@code JdbcTemplate} would need transaction
 * infrastructure this profile does not currently wire for raw JDBC, and a
 * lookup that quietly took a second pooled connection would sit
 * <em>outside</em> the transaction -- reading pre-rollback state and breaking
 * the atomicity the endpoint depends on. Taking one connection explicitly is
 * both closer to PHP and impossible to get subtly wrong.
 *
 * <p>Not a Spring bean: it is constructed per import, around the connection the
 * service borrowed.
 */
public class LegacyAttendanceImportStore {

	/** {@code $stIns} in both importers -- {@code method} is the literal {@code 'excel'}. */
	private static final String INSERT_ATTENDANCE =
			"INSERT INTO attendance (employee_id, check_in, check_out, method) VALUES (?, ?, ?, 'excel')";

	/** {@code $stDup}: same employee, same calendar day, whatever the time. */
	private static final String DUPLICATE_FOR_DAY =
			"SELECT id FROM attendance WHERE employee_id = ? AND DATE(check_in) = DATE(?)";

	private static final String EMPLOYEE_ID_BY_CODE_SCOPED =
			"SELECT e.id FROM employees e WHERE e.company_id = ? AND e.employee_code = ? LIMIT 1";

	/** {@code attendance_import_find_employee_id_by_code()} -- no LIMIT in the original. */
	private static final String EMPLOYEE_ID_BY_CODE =
			"SELECT id FROM employees WHERE company_id = ? AND employee_code = ?";

	private static final String EMPLOYEE_EXISTS =
			"SELECT COUNT(*) FROM employees WHERE id = ? AND company_id = ?";

	private static final String EMPLOYEE_CODE_EXISTS =
			"SELECT COUNT(*) FROM employees WHERE company_id = ? AND employee_code = ?";

	private static final String SHIFT_BELONGS =
			"SELECT COUNT(*) FROM shifts s WHERE s.id = ? AND s.company_id = ?";

	private static final String UPDATE_EMPLOYEE_CODE =
			"UPDATE employees SET employee_code = ? WHERE id = ? AND company_id = ?";

	/**
	 * The create-employee mapping's INSERT, column for column as PHP writes it
	 * -- {@code branch_id} included, and passed NULL.
	 *
	 * <p>{@code employees.branch_id} is {@code NOT NULL} with no default in the
	 * vendored schema, so whether this statement succeeds is the database's
	 * decision, not this class's. It is issued exactly as PHP issues it so that
	 * whatever MariaDB does to legacy, it does here.
	 */
	private static final String INSERT_EMPLOYEE = """
			INSERT INTO employees (
				company_id, branch_id, department_id, job_title_id, employee_code,
				expected_daily_hours, first_name, last_name, country_code, phone,
				password_hash, role, hire_date, is_mobile_attendance_enabled, is_active
			) VALUES (?, NULL, NULL, NULL, ?, ?, ?, ?, NULL, NULL, NULL, ?, ?, 1, 1)""";

	private static final String INSERT_SHIFT_ASSIGNMENT =
			"INSERT INTO employee_shift_assignments (employee_id, shift_id, effective_from) VALUES (?, ?, ?)";

	private final Connection connection;

	public LegacyAttendanceImportStore(Connection connection) {
		this.connection = connection;
	}

	/** {@code $stDup->execute(...); if ($stDup->fetch())}. */
	public boolean hasAttendanceOnDay(long employeeId, String checkIn) {
		try (PreparedStatement statement = connection.prepareStatement(DUPLICATE_FOR_DAY)) {
			statement.setLong(1, employeeId);
			statement.setString(2, checkIn);
			try (ResultSet rows = statement.executeQuery()) {
				return rows.next();
			}
		} catch (SQLException ex) {
			throw LegacyPdoException.from(ex);
		}
	}

	/** {@code $stIns->execute([$employeeId, $checkIn, $checkOut ?: null])}. */
	public void insertAttendance(long employeeId, String checkIn, String checkOut) {
		try (PreparedStatement statement = connection.prepareStatement(INSERT_ATTENDANCE)) {
			statement.setLong(1, employeeId);
			statement.setString(2, checkIn);
			if (checkOut == null) {
				statement.setNull(3, Types.VARCHAR);
			} else {
				statement.setString(3, checkOut);
			}
			statement.executeUpdate();
		} catch (SQLException ex) {
			throw LegacyPdoException.from(ex);
		}
	}

	/** {@code attendance_import_fetch_employee_by_code()} -- only its {@code id} is ever read. */
	public Long employeeIdByCodeScoped(long companyId, String normalizedCode) {
		return firstId(EMPLOYEE_ID_BY_CODE_SCOPED, companyId, normalizedCode, false);
	}

	/**
	 * {@code attendance_import_find_employee_id_by_code()}: the id, but only
	 * when it is positive -- {@code $id !== null && (int) $id > 0}.
	 */
	public Long employeeIdByCode(long companyId, String normalizedCode) {
		return firstId(EMPLOYEE_ID_BY_CODE, companyId, normalizedCode, true);
	}

	/** {@code (int) db_value('SELECT COUNT(*) ... id = ? AND company_id = ?')}. */
	public boolean employeeExistsInCompany(long employeeId, long companyId) {
		return count(EMPLOYEE_EXISTS, employeeId, companyId) > 0;
	}

	/** {@code employee_code_exists_in_company()} without its exclusion argument, which no caller here passes. */
	public boolean employeeCodeExistsInCompany(long companyId, String normalizedCode) {
		try (PreparedStatement statement = connection.prepareStatement(EMPLOYEE_CODE_EXISTS)) {
			statement.setLong(1, companyId);
			statement.setString(2, normalizedCode);
			try (ResultSet rows = statement.executeQuery()) {
				return rows.next() && rows.getLong(1) > 0;
			}
		} catch (SQLException ex) {
			throw LegacyPdoException.from(ex);
		}
	}

	/** {@code shift_belongs_to_company()}. */
	public boolean shiftBelongsToCompany(long shiftId, long companyId) {
		return count(SHIFT_BELONGS, shiftId, companyId) > 0;
	}

	/** The {@code sync_code} mapping's UPDATE. It is company-scoped; the return value is ignored. */
	public void updateEmployeeCode(String normalizedCode, long employeeId, long companyId) {
		try (PreparedStatement statement = connection.prepareStatement(UPDATE_EMPLOYEE_CODE)) {
			statement.setString(1, normalizedCode);
			statement.setLong(2, employeeId);
			statement.setLong(3, companyId);
			statement.executeUpdate();
		} catch (SQLException ex) {
			throw LegacyPdoException.from(ex);
		}
	}

	/**
	 * The create-employee mapping's INSERT, followed by
	 * {@code (int) $pdo->lastInsertId()}.
	 *
	 * @return the new id, or 0 when the driver reported no generated key --
	 *         which is what {@code (int) lastInsertId()} yields for the same
	 *         situation, and what the caller turns into a null resolution
	 */
	public long insertEmployee(
			long companyId, String employeeCode, double expectedDailyHours,
			String firstName, String lastName, String role, String hireDate) {
		try (PreparedStatement statement =
				connection.prepareStatement(INSERT_EMPLOYEE, Statement.RETURN_GENERATED_KEYS)) {
			statement.setLong(1, companyId);
			statement.setString(2, employeeCode);
			// PHP binds a float; the column is DECIMAL(5,2). BigDecimal keeps
			// the driver from choosing a binary float representation.
			statement.setBigDecimal(3, BigDecimal.valueOf(expectedDailyHours));
			statement.setString(4, firstName);
			statement.setString(5, lastName);
			statement.setString(6, role);
			statement.setString(7, hireDate);
			statement.executeUpdate();
			try (ResultSet keys = statement.getGeneratedKeys()) {
				return keys.next() ? keys.getLong(1) : 0L;
			}
		} catch (SQLException ex) {
			throw LegacyPdoException.from(ex);
		}
	}

	/** The shift assignment written straight after a created employee. */
	public void insertShiftAssignment(long employeeId, long shiftId, String effectiveFrom) {
		try (PreparedStatement statement = connection.prepareStatement(INSERT_SHIFT_ASSIGNMENT)) {
			statement.setLong(1, employeeId);
			statement.setLong(2, shiftId);
			statement.setString(3, effectiveFrom);
			statement.executeUpdate();
		} catch (SQLException ex) {
			throw LegacyPdoException.from(ex);
		}
	}

	private Long firstId(String sql, long companyId, String code, boolean positiveOnly) {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, companyId);
			statement.setString(2, code);
			try (ResultSet rows = statement.executeQuery()) {
				if (!rows.next()) {
					return null;
				}
				long id = rows.getLong(1);
				if (rows.wasNull()) {
					return null;
				}
				return positiveOnly && id <= 0 ? null : id;
			}
		} catch (SQLException ex) {
			throw LegacyPdoException.from(ex);
		}
	}

	private long count(String sql, long first, long second) {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, first);
			statement.setLong(2, second);
			try (ResultSet rows = statement.executeQuery()) {
				return rows.next() ? rows.getLong(1) : 0L;
			}
		} catch (SQLException ex) {
			throw LegacyPdoException.from(ex);
		}
	}

}
