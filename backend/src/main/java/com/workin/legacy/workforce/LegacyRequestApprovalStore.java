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

	private static final String EXCEPTION_TYPE_ACTIVE_FOR_COMPANY =
			"SELECT COUNT(*) FROM exception_types WHERE id = ? AND company_id = ? AND is_active = 1";

	private static final String LOWEST_ACTIVE_EXCEPTION_TYPE_ID =
			"SELECT id FROM exception_types WHERE company_id = ? AND is_active = 1 ORDER BY id ASC LIMIT 1";

	private static final String ATTENDANCE_EXISTS_FOR_DAY =
			"SELECT COUNT(*) FROM attendance WHERE employee_id = ? AND DATE(check_in) = ?";

	private static final String INSERT_ATTENDANCE_EXCEPTION =
			"INSERT INTO attendance (employee_id, check_in, method, exception_type_id) VALUES (?, ?, 'app', ?)";

	/** {@code notification_insert()}'s employee-recipient INSERT, same shape as {@link
	 * com.workin.legacy.notifications.LegacyNotifications}'s pooled one -- issued here instead
	 * because {@code request_approve()} writes it on the same PDO instance as the rest of the
	 * transaction, before the commit. */
	private static final String INSERT_NOTIFICATION = """
			INSERT INTO notifications (
				company_id, recipient_kind, from_employee_id, to_employee_id,
				title, body, notification_type, reference_type, reference_id
			) VALUES (?, 'employee', ?, ?, ?, ?, ?, ?, ?)""";

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

	/**
	 * {@code exception_type_resolve_for_company()}
	 * ({@code exception_types_helper.php:20-45}), on this same connection --
	 * {@link com.workin.legacy.attendance.LegacyExceptionTypeService#resolveForCompany}
	 * is a pooled JPA call, and this transaction already holds one connection
	 * for its whole duration; calling that pooled path here would be a nested
	 * checkout, which under a concurrent burst of approvals can starve the
	 * pool (each in-flight approval would need two connections at once) and
	 * time out at the datasource's 5-second connection timeout.
	 */
	public long resolveExceptionTypeForCompany(long companyId, Long exceptionTypeId) {
		if (exceptionTypeId != null && exceptionTypeId > 0 && exceptionTypeActiveForCompany(exceptionTypeId, companyId)) {
			return exceptionTypeId;
		}
		try (PreparedStatement statement = connection.prepareStatement(LOWEST_ACTIVE_EXCEPTION_TYPE_ID)) {
			statement.setLong(1, companyId);
			try (ResultSet rows = statement.executeQuery()) {
				return rows.next() ? rows.getLong(1) : 0L;
			}
		} catch (SQLException ex) {
			throw LegacyPdoException.from(ex);
		}
	}

	private boolean exceptionTypeActiveForCompany(long exceptionTypeId, long companyId) {
		try (PreparedStatement statement = connection.prepareStatement(EXCEPTION_TYPE_ACTIVE_FOR_COMPANY)) {
			statement.setLong(1, exceptionTypeId);
			statement.setLong(2, companyId);
			try (ResultSet rows = statement.executeQuery()) {
				return rows.next() && rows.getLong(1) > 0;
			}
		} catch (SQLException ex) {
			throw LegacyPdoException.from(ex);
		}
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

	/**
	 * {@code notification_insert()} ({@code notifications.php:52-115}), the
	 * {@code recipient_kind = 'employee'} shape, on this same connection so its
	 * failure rolls back the whole approval -- {@code request_approve()} calls
	 * {@code notification_request_decision_to_employee()} inside its own
	 * transaction, before {@code db_pdo()->commit()}.
	 *
	 * @return the generated id, as {@code get_last_inserted_id()} does
	 */
	public long insertNotification(
			long companyId, long toEmployeeId, Long fromEmployeeId, String type, String title, String body,
			String referenceType, Long referenceId) {
		try (PreparedStatement statement =
				connection.prepareStatement(INSERT_NOTIFICATION, PreparedStatement.RETURN_GENERATED_KEYS)) {
			statement.setLong(1, companyId);
			if (fromEmployeeId != null && fromEmployeeId > 0) {
				statement.setLong(2, fromEmployeeId);
			} else {
				statement.setNull(2, Types.INTEGER);
			}
			statement.setLong(3, toEmployeeId);
			statement.setString(4, title);
			statement.setString(5, body);
			statement.setString(6, type);
			statement.setString(7, referenceType);
			if (referenceId == null) {
				statement.setNull(8, Types.INTEGER);
			} else {
				statement.setLong(8, referenceId);
			}
			statement.executeUpdate();
			try (ResultSet keys = statement.getGeneratedKeys()) {
				return keys.next() ? keys.getLong(1) : 0L;
			}
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
