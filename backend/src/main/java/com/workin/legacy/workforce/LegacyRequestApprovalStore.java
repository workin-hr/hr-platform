package com.workin.legacy.workforce;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.workin.legacy.LegacyJdbcValues;
import com.workin.legacy.LegacyPdoException;

/**
 * Every statement {@code requests/approve.php} issues, on <b>one</b>
 * connection -- the same shape {@link
 * com.workin.legacy.attendance.spreadsheet.LegacyAttendanceImportStore} uses
 * and for the same reason: PHP holds a single PDO instance, so
 * {@code $pdo->beginTransaction()} in {@code request_approve()} encloses the
 * {@code requests} update, the {@code leave_balance} read/write and the
 * {@code attendance} inserts alike. Not a Spring bean: constructed per
 * approval, around the connection the service borrowed.
 */
public class LegacyRequestApprovalStore {

	private static final String FOR_APPROVAL = """
			SELECT r.*, t.deduct_balance, t.counts_as_paid_leave, t.add_attendance_exception,
				t.exception_type_id, t.name AS request_type_name
			FROM requests r
			JOIN request_types t ON t.id = r.request_type_id
			JOIN employees e ON e.id = r.employee_id
			WHERE r.id = ? AND e.company_id = ?""";

	private static final String UPDATE_STATUS =
			"UPDATE requests SET status = ?, reply = ?, approver_id = ?, decided_at = NOW() WHERE id = ?";

	private static final String LEAVE_BALANCE_ROW =
			"SELECT total_days, used_days FROM leave_balance WHERE employee_id = ? AND year = ?";

	private static final String LEAVE_BALANCE_EXISTS =
			"SELECT COUNT(*) FROM leave_balance WHERE employee_id = ? AND year = ?";

	private static final String INCREMENT_USED_DAYS =
			"UPDATE leave_balance SET used_days = used_days + ? WHERE employee_id = ? AND year = ?";

	private static final String INSERT_LEAVE_BALANCE =
			"INSERT INTO leave_balance (employee_id, year, total_days, used_days) VALUES (?, ?, ?, ?)";

	/** {@code company_setting_selected_values($company_id, 'monthly_leave_accrual')}. */
	private static final String MONTHLY_LEAVE_ACCRUAL_VALUES = """
			SELECT sav.value
			FROM setting_definitions sd
			INNER JOIN company_settings cs ON cs.setting_definition_id = sd.id AND cs.company_id = ?
			INNER JOIN company_setting_values csv ON csv.company_setting_id = cs.id
			INNER JOIN setting_allowed_values sav ON sav.id = csv.setting_allowed_value_id
			WHERE sd.setting_key = 'monthly_leave_accrual'
			ORDER BY sav.sort_order ASC, sav.id ASC""";

	private static final String ATTENDANCE_EXISTS_FOR_DAY =
			"SELECT COUNT(*) FROM attendance WHERE employee_id = ? AND DATE(check_in) = ?";

	private static final String INSERT_ATTENDANCE_EXCEPTION =
			"INSERT INTO attendance (employee_id, check_in, method, exception_type_id) VALUES (?, ?, 'app', ?)";

	/** PHP's {@code (float)} string cast: the longest leading numeric prefix, or 0.0 with none. */
	private static final Pattern LEADING_NUMBER =
			Pattern.compile("^\\s*[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");

	private final Connection connection;

	public LegacyRequestApprovalStore(Connection connection) {
		this.connection = connection;
	}

	/** {@code request_fetch_for_approval()} ({@code request_actions_helper.php:40-56}). */
	public Map<String, Object> forApproval(long id, long companyId) {
		try (PreparedStatement statement = connection.prepareStatement(FOR_APPROVAL)) {
			statement.setLong(1, id);
			statement.setLong(2, companyId);
			try (ResultSet rows = statement.executeQuery()) {
				return rows.next() ? rowOf(rows) : null;
			}
		} catch (SQLException ex) {
			throw LegacyPdoException.from(ex);
		}
	}

	/**
	 * {@code approve.php}/{@code reject.php}'s status update. {@code approverId}
	 * is a deliberate addition over legacy, matching {@code reject}'s own --
	 * see {@link LegacyRequestService#reject}.
	 */
	public void updateStatus(long id, String status, String reply, Long approverId) {
		try (PreparedStatement statement = connection.prepareStatement(UPDATE_STATUS)) {
			statement.setString(1, status);
			bindNullableString(statement, 2, reply);
			if (approverId == null) {
				statement.setNull(3, Types.BIGINT);
			} else {
				statement.setLong(3, approverId);
			}
			statement.setLong(4, id);
			statement.executeUpdate();
		} catch (SQLException ex) {
			throw LegacyPdoException.from(ex);
		}
	}

	public record LeaveBalanceRow(double totalDays, double usedDays) {
	}

	public LeaveBalanceRow leaveBalance(long employeeId, int year) {
		try (PreparedStatement statement = connection.prepareStatement(LEAVE_BALANCE_ROW)) {
			statement.setLong(1, employeeId);
			statement.setInt(2, year);
			try (ResultSet rows = statement.executeQuery()) {
				return rows.next() ? new LeaveBalanceRow(rows.getDouble(1), rows.getDouble(2)) : null;
			}
		} catch (SQLException ex) {
			throw LegacyPdoException.from(ex);
		}
	}

	public boolean leaveBalanceExists(long employeeId, int year) {
		return count(LEAVE_BALANCE_EXISTS, employeeId, year) > 0;
	}

	public void incrementUsedDays(long employeeId, int year, int days) {
		try (PreparedStatement statement = connection.prepareStatement(INCREMENT_USED_DAYS)) {
			statement.setInt(1, days);
			statement.setLong(2, employeeId);
			statement.setInt(3, year);
			statement.executeUpdate();
		} catch (SQLException ex) {
			throw LegacyPdoException.from(ex);
		}
	}

	public void insertLeaveBalance(long employeeId, int year, double totalDays, int usedDays) {
		try (PreparedStatement statement = connection.prepareStatement(INSERT_LEAVE_BALANCE)) {
			statement.setLong(1, employeeId);
			statement.setInt(2, year);
			statement.setDouble(3, totalDays);
			statement.setInt(4, usedDays);
			statement.executeUpdate();
		} catch (SQLException ex) {
			throw LegacyPdoException.from(ex);
		}
	}

	/**
	 * {@code company_setting_selected_values($company_id, 'monthly_leave_accrual')[0] ?? 21.0}.
	 * The values PHP's own {@code array_filter} already strips empty strings
	 * from before indexing, so "first non-empty value in order, else 21.0" is
	 * exactly equivalent to indexing the filtered array.
	 */
	public double monthlyLeaveAccrualDefault(long companyId) {
		try (PreparedStatement statement = connection.prepareStatement(MONTHLY_LEAVE_ACCRUAL_VALUES)) {
			statement.setLong(1, companyId);
			try (ResultSet rows = statement.executeQuery()) {
				while (rows.next()) {
					String value = rows.getString(1);
					if (value != null && !value.isEmpty()) {
						return phpFloatCast(value);
					}
				}
			}
		} catch (SQLException ex) {
			throw LegacyPdoException.from(ex);
		}
		return 21.0;
	}

	private static double phpFloatCast(String value) {
		Matcher matcher = LEADING_NUMBER.matcher(value);
		return matcher.find() ? Double.parseDouble(matcher.group()) : 0.0;
	}

	public boolean attendanceExistsForDay(long employeeId, String date) {
		return count(ATTENDANCE_EXISTS_FOR_DAY, employeeId, date) > 0;
	}

	/** {@code $date . ' 00:00:00'}, method {@code 'app'}. */
	public void insertAttendanceException(long employeeId, String date, long exceptionTypeId) {
		try (PreparedStatement statement = connection.prepareStatement(INSERT_ATTENDANCE_EXCEPTION)) {
			statement.setLong(1, employeeId);
			statement.setString(2, date + " 00:00:00");
			statement.setLong(3, exceptionTypeId);
			statement.executeUpdate();
		} catch (SQLException ex) {
			throw LegacyPdoException.from(ex);
		}
	}

	private long count(String sql, long employeeId, Object second) {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, employeeId);
			if (second instanceof Integer year) {
				statement.setInt(2, year);
			} else {
				statement.setString(2, (String) second);
			}
			try (ResultSet rows = statement.executeQuery()) {
				return rows.next() ? rows.getLong(1) : 0L;
			}
		} catch (SQLException ex) {
			throw LegacyPdoException.from(ex);
		}
	}

	private static void bindNullableString(PreparedStatement statement, int index, String value) throws SQLException {
		if (value == null) {
			statement.setNull(index, Types.VARCHAR);
		} else {
			statement.setString(index, value);
		}
	}

	private static Map<String, Object> rowOf(ResultSet rows) throws SQLException {
		ResultSetMetaData meta = rows.getMetaData();
		Map<String, Object> row = new LinkedHashMap<>();
		for (int column = 1; column <= meta.getColumnCount(); column++) {
			row.put(meta.getColumnLabel(column), LegacyJdbcValues.read(rows, column, meta.getColumnType(column)));
		}
		return row;
	}

}
