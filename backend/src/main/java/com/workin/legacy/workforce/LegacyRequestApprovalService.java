package com.workin.legacy.workforce;

import java.sql.Connection;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyPhpStrtotime;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.attendance.LegacyExceptionTypeService;
import com.workin.legacy.notifications.LegacyPushDelivery;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyMessages;

/** {@code request_approve()} from {@code request_actions_helper.php}. */
@Service
public class LegacyRequestApprovalService {

	private final DataSource dataSource;
	private final LegacyRequestApprovalStore store;
	private final LegacyExceptionTypeService exceptionTypes;
	private final LegacyPushDelivery pushDelivery;
	private final LegacyMessages messages;
	private final LegacyClock clock;

	public LegacyRequestApprovalService(
			DataSource legacyDataSource,
			LegacyRequestApprovalStore store,
			LegacyExceptionTypeService exceptionTypes,
			LegacyPushDelivery pushDelivery,
			LegacyMessages messages,
			LegacyClock clock) {
		this.dataSource = legacyDataSource;
		this.store = store;
		this.exceptionTypes = exceptionTypes;
		this.pushDelivery = pushDelivery;
		this.messages = messages;
		this.clock = clock;
	}

	/**
	 * Fetch and balance validation intentionally precede {@code beginTransaction},
	 * but use the same physical connection the writes later use, matching PHP's
	 * one PDO instance (D-100).
	 */
	public void approve(long requestId, long companyId, Long approverId, String reply, String locale) {
		try (Connection connection = dataSource.getConnection()) {
			Map<String, Object> request = store.request(connection, requestId, companyId);
			if (request == null) {
				throw new LegacyApiException(404, "not_found");
			}
			if (!"pending".equals(String.valueOf(request.get("status")))) {
				throw new LegacyApiException(409, "already_decided");
			}

			long employeeId = number(request.get("employee_id"));
			String fromDate = string(request.get("from_date"));
			String toDate = string(request.get("to_date"));
			DateSpan span = dateSpan(fromDate, toDate);
			Map<String, Object> balance = null;
			if (!LegacyValues.isPhpEmpty(request.get("deduct_balance"))) {
				balance = store.leaveBalance(connection, employeeId, span.year());
				if (balance != null) {
					double total = decimal(balance.get("total_days"));
					double used = decimal(balance.get("used_days"));
					if (Math.max(0.0d, total - used) < span.days()) {
						throw new LegacyApiException(422, "insufficient_leave_balance");
					}
				}
			}

			boolean previousAutoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			try {
				store.approveRequest(connection, requestId, reply, approverId);

				if (!LegacyValues.isPhpEmpty(request.get("deduct_balance"))) {
					double defaultDays = balance == null
							? store.defaultAnnualLeaveDays(connection, companyId)
							: 0.0d;
					store.applyLeaveDeduction(
							connection, balance, employeeId, span.year(), span.days(), defaultDays);
				}

				if (!LegacyValues.isPhpEmpty(request.get("add_attendance_exception"))) {
					Long requestedType = positiveLongOrNull(request.get("exception_type_id"));
					long resolved = exceptionTypes.resolveForCompany(companyId, requestedType);
					if (resolved > 0) {
						applyAttendanceExceptions(connection, employeeId, span.start(), span.end(), resolved);
					}
				}

				String title = messages.translate(locale, "request_approved", null);
				String body = LegacyValues.phpTrim(reply).isEmpty()
						? messages.translate(locale, "request_approved_msg",
								Map.of("type", string(request.get("request_type_name"))))
						: reply;
				long notificationId = store.insertDecisionNotification(
						connection, companyId, employeeId, approverId, title, body, requestId);
				try {
					pushDelivery.sendToEmployee(employeeId, title, body, Map.of(
							"notification_id", String.valueOf(notificationId),
							"notification_type", "request_approved"));
				} catch (Throwable ignored) { // NOPMD -- PHP catches Throwable around push delivery
					// The notification row and the approval transaction remain authoritative.
				}

				connection.commit();
			} catch (Throwable failure) { // NOPMD -- request_approve catches Throwable and rolls back
				try {
					connection.rollback();
				} catch (Throwable rollbackFailure) { // PHP rollback failure masks the original too
					rollbackFailure.addSuppressed(failure);
					throwUnchecked(rollbackFailure);
				}
				throwUnchecked(failure);
			} finally {
				try {
					connection.setAutoCommit(previousAutoCommit);
				} catch (Throwable ignored) {
					// Connection close follows immediately; do not replace a business failure.
				}
			}
		} catch (LegacyApiException ex) {
			throw ex;
		} catch (RuntimeException ex) {
			throw ex;
		} catch (Throwable ex) {
			throw new IllegalStateException("Legacy request approval failed", ex);
		}
	}

	private void applyAttendanceExceptions(
			Connection connection, long employeeId, LocalDate start, LocalDate end, long exceptionTypeId)
			throws Exception {
		if (start == null || end == null || end.isBefore(start)) {
			return;
		}
		for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
			String lexical = date.toString();
			if (!store.attendanceExists(connection, employeeId, lexical)) {
				store.insertAttendanceException(connection, employeeId, lexical, exceptionTypeId);
			}
		}
	}

	private DateSpan dateSpan(String from, String to) {
		LocalDate start = LegacyPhpStrtotime.dateOf(from, clock.today());
		LocalDate end = LegacyPhpStrtotime.dateOf(to, clock.today());
		int days = (start == null || end == null || end.isBefore(start))
				? 1 : Math.toIntExact(ChronoUnit.DAYS.between(start, end) + 1);
		// PHP's date('Y', strtotime($from)) raises when strtotime returns false.
		if (start == null) {
			throw new IllegalStateException("date(): Argument #2 ($timestamp) must be of type ?int, false given");
		}
		return new DateSpan(start, end, days, start.getYear());
	}

	private static long number(Object value) {
		return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
	}

	private static double decimal(Object value) {
		if (value == null) {
			return 0.0d;
		}
		try {
			return Double.parseDouble(String.valueOf(value));
		} catch (NumberFormatException ignored) {
			return 0.0d;
		}
	}

	private static String string(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private static Long positiveLongOrNull(Object value) {
		if (value == null) {
			return null;
		}
		long result = LegacyValues.toPhpLong(value);
		return result > 0 ? result : null;
	}

	private static void throwUnchecked(Throwable failure) {
		if (failure instanceof RuntimeException runtime) {
			throw runtime;
		}
		if (failure instanceof Error error) {
			throw error;
		}
		throw new IllegalStateException("Legacy request approval failed", failure);
	}

	private record DateSpan(LocalDate start, LocalDate end, int days, int year) {
	}
}
