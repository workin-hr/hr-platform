package com.workin.legacy.workforce;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.workin.legacy.LegacyJdbcValues;
import com.workin.legacy.LegacyValues;

/**
 * Connection-scoped persistence for {@code request_approve()}.
 *
 * <p>D-100 deliberately keeps this separate from the pooled
 * {@link LegacyRequestStore}: PHP fetches the request once from one PDO
 * connection, performs the balance pre-check on that same connection, then
 * starts a transaction and writes the request, leave balance, attendance and
 * notification before committing it. Every method here therefore receives the
 * caller-owned JDBC connection explicitly.
 */
@Component
public class LegacyRequestApprovalStore {

	private static final String REQUEST_FOR_APPROVAL = """
			SELECT r.*, t.deduct_balance, t.counts_as_paid_leave,
			       t.add_attendance_exception, t.exception_type_id,
			       t.name AS request_type_name
			FROM requests r
			JOIN request_types t ON t.id = r.request_type_id
			JOIN employees e ON e.id = r.employee_id
			WHERE r.id = ? AND e.company_id = ?""";

	public Map<String, Object> request(Connection connection, long requestId, long companyId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(REQUEST_FOR_APPROVAL)) {
			statement.setLong(1, requestId);
			statement.setLong(2, companyId);
			try (ResultSet rs = statement.executeQuery()) {
				return rs.next() ? row(rs) : null;
			}
		}
	}

	/** First statement of PHP's insufficient-balance pre-check. */
	public boolean leaveBalanceExists(Connection connection, long employeeId, int year) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT COUNT(*) FROM leave_balance WHERE employee_id = ? AND year = ?")) {
			statement.setLong(1, employeeId);
			statement.setInt(2, year);
			try (ResultSet rs = statement.executeQuery()) {
				rs.next();
				return rs.getLong(1) > 0;
			}
		}
	}

	/** Second statement of PHP's insufficient-balance pre-check. */
	public Map<String, Object> leaveBalance(Connection connection, long employeeId, int year) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT * FROM leave_balance WHERE employee_id = ? AND year = ? LIMIT 1")) {
			statement.setLong(1, employeeId);
			statement.setInt(2, year);
			try (ResultSet rs = statement.executeQuery()) {
				return rs.next() ? row(rs) : null;
			}
		}
	}

	public void approveRequest(
			Connection connection, long requestId, String reply, Long approverId) throws SQLException {
		// approver_id is the deliberate Wave-12.7 mapping correction already
		// applied to reject.php in D-100. Legacy itself leaves the column null.
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE requests
				SET status = 'approved', reply = ?, approver_id = ?, decided_at = NOW()
				WHERE id = ?""")) {
			if (reply.isEmpty()) {
				statement.setNull(1, Types.VARCHAR);
			} else {
				statement.setString(1, reply);
			}
			if (approverId == null || approverId <= 0) {
				statement.setNull(2, Types.BIGINT);
			} else {
				statement.setLong(2, approverId);
			}
			statement.setLong(3, requestId);
			statement.executeUpdate();
		}
	}

	public void applyLeaveDeduction(
			Connection connection, Map<String, Object> balance, long employeeId,
			int year, int days, double defaultTotalDays) throws SQLException {
		if (balance != null) {
			try (PreparedStatement statement = connection.prepareStatement(
					"UPDATE leave_balance SET used_days = used_days + ? WHERE id = ?")) {
				statement.setInt(1, days);
				statement.setLong(2, number(balance.get("id")));
				statement.executeUpdate();
			}
			return;
		}

		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO leave_balance (employee_id, year, total_days, used_days)
				VALUES (?, ?, ?, ?)""")) {
			statement.setLong(1, employeeId);
			statement.setInt(2, year);
			statement.setDouble(3, defaultTotalDays);
			statement.setInt(4, days);
			statement.executeUpdate();
		}
	}

	/** First non-empty selected {@code monthly_leave_accrual}, else 21.0. */
	public double defaultAnnualLeaveDays(Connection connection, long companyId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT sav.value
				FROM setting_definitions sd
				JOIN company_settings cs
				  ON cs.setting_definition_id = sd.id AND cs.company_id = ?
				JOIN company_setting_values csv ON csv.company_setting_id = cs.id
				JOIN setting_allowed_values sav ON sav.id = csv.setting_allowed_value_id
				WHERE sd.setting_key = 'monthly_leave_accrual'
				ORDER BY sav.sort_order ASC, sav.id ASC""")) {
			statement.setLong(1, companyId);
			try (ResultSet rs = statement.executeQuery()) {
				while (rs.next()) {
					String raw = rs.getString(1);
					if (raw != null && !raw.isEmpty()) {
						return LegacyValues.toPhpDecimal(raw).doubleValue();
					}
				}
			}
		}
		return 21.0d;
	}

	/** {@code exception_type_resolve_for_company()} on the caller-owned D-100 connection. */
	public long resolveExceptionTypeForCompany(
			Connection connection, long companyId, Long exceptionTypeId) throws SQLException {
		if (exceptionTypeId != null && exceptionTypeId > 0) {
			try (PreparedStatement statement = connection.prepareStatement("""
					SELECT COUNT(*) FROM exception_types
					WHERE id = ? AND company_id = ? AND is_active = 1""")) {
				statement.setLong(1, exceptionTypeId);
				statement.setLong(2, companyId);
				try (ResultSet rs = statement.executeQuery()) {
					rs.next();
					if (rs.getLong(1) > 0) {
						return exceptionTypeId;
					}
				}
			}
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT id FROM exception_types
				WHERE company_id = ? AND is_active = 1
				ORDER BY id ASC LIMIT 1""")) {
			statement.setLong(1, companyId);
			try (ResultSet rs = statement.executeQuery()) {
				return rs.next() ? rs.getLong(1) : 0L;
			}
		}
	}

	public boolean attendanceExists(Connection connection, long employeeId, String date) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT COUNT(*) FROM attendance WHERE employee_id = ? AND DATE(check_in) = ?")) {
			statement.setLong(1, employeeId);
			statement.setString(2, date);
			try (ResultSet rs = statement.executeQuery()) {
				rs.next();
				return rs.getLong(1) > 0;
			}
		}
	}

	public void insertAttendanceException(
			Connection connection, long employeeId, String date, long exceptionTypeId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO attendance (employee_id, check_in, method, exception_type_id)
				VALUES (?, ?, 'app', ?)""")) {
			statement.setLong(1, employeeId);
			statement.setString(2, date + " 00:00:00");
			statement.setLong(3, exceptionTypeId);
			statement.executeUpdate();
		}
	}

	public long insertDecisionNotification(
			Connection connection, long companyId, long employeeId, Long approverId,
			String title, String body, long requestId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO notifications (
				  company_id, recipient_kind, from_employee_id, to_employee_id,
				  title, body, notification_type, reference_type, reference_id
				) VALUES (?, 'employee', ?, ?, ?, ?, 'request_approved', 'request', ?)
				""", Statement.RETURN_GENERATED_KEYS)) {
			statement.setLong(1, companyId);
			if (approverId == null || approverId <= 0) {
				statement.setNull(2, Types.BIGINT);
			} else {
				statement.setLong(2, approverId);
			}
			statement.setLong(3, employeeId);
			statement.setString(4, title);
			statement.setString(5, body);
			statement.setLong(6, requestId);
			statement.executeUpdate();
			try (ResultSet keys = statement.getGeneratedKeys()) {
				return keys.next() ? keys.getLong(1) : 0L;
			}
		}
	}

	private static Map<String, Object> row(ResultSet rs) throws SQLException {
		ResultSetMetaData meta = rs.getMetaData();
		Map<String, Object> row = new LinkedHashMap<>();
		for (int column = 1; column <= meta.getColumnCount(); column++) {
			row.put(meta.getColumnLabel(column), LegacyJdbcValues.read(rs, column, meta.getColumnType(column)));
		}
		return row;
	}

	private static long number(Object value) {
		return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
	}
}
