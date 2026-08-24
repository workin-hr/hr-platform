package com.workin.legacy.workforce;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyPagination;
import com.workin.legacy.LegacyPhpStrtotime;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.attendance.LegacyExceptionTypeService;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.employees.LegacyEmployeeStore;
import com.workin.legacy.notifications.LegacyNotifications;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyMessages;

/**
 * {@code hr-legacy/apis/api/requests/*.php} (Wave 12.7).
 *
 * <h2>{@code not_found}/{@code already_decided} are not one status code</h2>
 * <p>Measured, not normalised: {@code one.php} and {@code request_approve()}
 * answer a missing/foreign id with an explicit 404, and {@code approve}'s
 * status conflict is an explicit 409. {@code create.php}'s type lookup,
 * {@code update.php}, {@code delete.php} and {@code reject.php} all call
 * {@code fail()} with <b>no</b> status argument for the identical semantic
 * failures, which defaults to 400. Five endpoints, three different codes for
 * "not found" depending on which one answers -- reproduced exactly rather
 * than unified.
 *
 * <h2>{@code approve}'s transaction</h2>
 * <p>{@code request_approve()} ({@code request_actions_helper.php:201-249})
 * writes {@code requests}, {@code leave_balance} and {@code attendance} all
 * inside one PDO transaction, sharing PHP's single global connection with the
 * pre-transaction fetch and balance check that precede it. {@link #approve}
 * reproduces the whole thing on <b>one</b> explicit {@link
 * java.sql.Connection}, borrowed for the entire call and passed into a single
 * {@link LegacyRequestApprovalStore} -- the same shape
 * {@link com.workin.legacy.attendance.spreadsheet.LegacyAttendanceImportService}
 * uses, just started earlier: PHP never re-reads {@code $request} once
 * fetched, so neither does this. Only the notification moves off that
 * connection, sent after it closes on the ordinary pooled {@link
 * LegacyNotifications} -- {@code LegacyAttendanceImportService}'s own "(7) the
 * notification, after the commit" precedent, safe here because nothing reads
 * the notification back.
 */
@Service
public class LegacyRequestService {

	private static final Set<String> VALID_STATUSES = Set.of("pending", "approved", "rejected");

	private final LegacyRequestStore store;
	private final LegacyRequestTypeStore requestTypeStore;
	private final LegacyEmployeeStore employeeStore;
	private final LegacyExceptionTypeService exceptionTypes;
	private final LegacyNotifications notifications;
	private final LegacyMessages messages;
	private final LegacyClock clock;
	private final DataSource dataSource;

	public LegacyRequestService(
			LegacyRequestStore store, LegacyRequestTypeStore requestTypeStore,
			LegacyEmployeeStore employeeStore, LegacyExceptionTypeService exceptionTypes,
			LegacyNotifications notifications, LegacyMessages messages, LegacyClock clock,
			DataSource legacyDataSource) {
		this.store = store;
		this.requestTypeStore = requestTypeStore;
		this.employeeStore = employeeStore;
		this.exceptionTypes = exceptionTypes;
		this.notifications = notifications;
		this.messages = messages;
		this.clock = clock;
		this.dataSource = legacyDataSource;
	}

	/** A page plus its {@code pagination_meta()}. */
	public record Page(List<Map<String, Object>> rows, Map<String, Object> meta) {
	}

	/**
	 * {@code requests/list.php}. The role branch is mutually exclusive with the
	 * company branch, exactly as PHP's {@code if/else} reads: an EMPLOYEE never
	 * reaches the manager-scope or {@code employee_id} filter, and a company
	 * role never gets the bare {@code r.employee_id = self} clause.
	 */
	public Page list(LegacyRequestContext context, LegacyQueryParameters query) {
		Long ownEmployeeId = null;
		Long companyId = null;
		Long managerEmployeeId = null;
		Long filterEmployeeId = null;

		if (context.role() == LegacyEmployee.Role.EMPLOYEE) {
			ownEmployeeId = context.employeeId();
		} else {
			companyId = context.companyId();
			if (context.role() == LegacyEmployee.Role.MANAGER) {
				managerEmployeeId = context.employeeId();
			}
			Object employeeIdParam = query.value("employee_id");
			if (!LegacyValues.isPhpEmpty(employeeIdParam)) {
				filterEmployeeId = LegacyValues.toPhpLong(employeeIdParam);
			}
		}

		String status = null;
		Object statusParam = query.value("status");
		if (!LegacyValues.isPhpEmpty(statusParam)) {
			String candidate = LegacyValues.toPhpString(statusParam);
			status = VALID_STATUSES.contains(candidate) ? candidate : null;
		}

		Long typeId = null;
		Object typeIdParam = query.value("type_id");
		if (!LegacyValues.isPhpEmpty(typeIdParam)) {
			typeId = LegacyValues.toPhpLong(typeIdParam);
		}

		// Request::DATE_FROM / DATE_TO (request.php:26-27) are the wire keys
		// "from"/"to", not "date_from"/"date_to" -- D-080, already the shape
		// LegacyOfficialHolidayService's own date filter uses.
		String dateFrom = nonEmptyQueryValue(query, "from");
		String dateTo = nonEmptyQueryValue(query, "to");
		String search = LegacyPagination.searchQueryParam(query);
		LegacyPagination.Params pagination = LegacyPagination.params(query);

		LegacyRequestStore.ListFilter filter = new LegacyRequestStore.ListFilter(
				ownEmployeeId, companyId, managerEmployeeId, filterEmployeeId,
				status, typeId, dateFrom, dateTo, search);

		long total = store.count(filter);
		return new Page(store.list(filter, pagination), LegacyPagination.meta(total, pagination));
	}

	/**
	 * {@code requests/one.php}: a missing/foreign id is an explicit 404, and
	 * the ownership/company/manager-scope checks all answer an explicit 403.
	 */
	public Map<String, Object> one(LegacyRequestContext context, long id) {
		Map<String, Object> request = store.byId(id);
		if (request == null) {
			throw new LegacyApiException(404, "not_found");
		}

		if (context.role() == LegacyEmployee.Role.EMPLOYEE) {
			if (LegacyValues.toPhpLong(request.get("employee_id")) != context.employeeId()) {
				throw new LegacyApiException(403, "forbidden_insufficient_role");
			}
			return request;
		}

		long employeeCompanyId = store.employeeCompanyId(LegacyValues.toPhpLong(request.get("employee_id")));
		if (employeeCompanyId != context.companyId()) {
			throw new LegacyApiException(403, "forbidden_insufficient_role");
		}
		if (context.role() == LegacyEmployee.Role.MANAGER && !employeeStore.managerCanAccessEmployeeBranch(
				context.employeeId(), LegacyValues.toPhpLong(request.get("employee_id")), context.companyId())) {
			throw new LegacyApiException(403, "forbidden_insufficient_role");
		}
		return request;
	}

	/**
	 * {@code requests/create.php}. The type lookup is the plain
	 * {@code request_types} row (no {@code is_active} filter, matching PHP), so
	 * a request against a deactivated type still succeeds.
	 */
	public Map<String, Object> create(LegacyRequestContext context, String locale, Map<String, Object> body) {
		required(body, "request_type_id", "from_date", "to_date");

		long requestTypeId = LegacyValues.toPhpLong(body.get("request_type_id"));
		String fromDate = LegacyValues.toPhpString(body.get("from_date"));
		String toDate = LegacyValues.toPhpString(body.get("to_date"));
		String fromTime = optionalTimeValue(body.get("from_time"));
		String toTime = optionalTimeValue(body.get("to_time"));
		String notes = body.get("notes") == null ? "" : LegacyValues.toPhpString(body.get("notes"));

		if (requestTypeStore.byIdForCompany(context.companyId(), requestTypeId) == null) {
			throw new LegacyApiException(400, "not_found");
		}

		long id = store.insert(context.employeeId(), requestTypeId, fromDate, toDate, fromTime, toTime, notes);
		Map<String, Object> inserted = store.byIdWithTypeAndEmployeeName(id);

		notifications.toCompany(
				context.companyId(), context.employeeId(), "request_submitted",
				messages.translate(locale, "notif_request_submitted_title", null),
				messages.translate(locale, "notif_request_submitted_body", Map.of(
						"employee", LegacyValues.toPhpString(inserted.get("employee_name")),
						"type", LegacyValues.toPhpString(inserted.get("request_type_name")),
						"from", fromDate,
						"to", toDate)));

		return inserted;
	}

	/**
	 * {@code requests/update.php}. The whitelist runs before the ownership and
	 * status lookup -- an empty body against a foreign or already-decided id
	 * answers {@code nothing_to_update}, never {@code not_found} or
	 * {@code already_decided}, exactly as {@code request_types/update.php}'s
	 * own D-088 note already established for this ordering.
	 *
	 * <p>Unlike legacy, a supplied {@code request_type_id} is validated against
	 * the caller's own company before the write, the same check {@link #create}
	 * already applies. PHP's {@code update.php} skips this entirely -- the FK
	 * only proves the type exists somewhere, not that it belongs to this
	 * tenant -- so an unvalidated port would let an employee point their
	 * request at another company's type, which then leaks that company's type
	 * name back through this same response and {@code reject.php}'s. A
	 * deliberate, security-motivated narrowing, not a preserved measurement.
	 */
	public Map<String, Object> update(LegacyRequestContext context, long id, Map<String, Object> rawBody) {
		Map<String, Object> body = new LinkedHashMap<>(rawBody == null ? Map.of() : rawBody);
		if (body.containsKey("from_time")) {
			body.put("from_time", optionalTimeValue(body.get("from_time")));
		}
		if (body.containsKey("to_time")) {
			body.put("to_time", optionalTimeValue(body.get("to_time")));
		}

		List<String> columns = new ArrayList<>();
		List<Object> values = new ArrayList<>();
		for (String field : List.of("from_date", "to_date", "from_time", "to_time", "notes", "request_type_id")) {
			if (body.containsKey(field)) {
				columns.add(field);
				values.add(body.get(field));
			}
		}
		if (columns.isEmpty()) {
			throw new LegacyApiException(400, "nothing_to_update");
		}

		Map<String, Object> request = store.byIdOwnedByEmployee(id, context.employeeId());
		if (request == null) {
			throw new LegacyApiException(400, "not_found");
		}
		if (!"pending".equals(request.get("status"))) {
			throw new LegacyApiException(400, "already_decided");
		}

		if (body.containsKey("request_type_id")) {
			long requestTypeId = LegacyValues.toPhpLong(body.get("request_type_id"));
			if (requestTypeStore.byIdForCompany(context.companyId(), requestTypeId) == null) {
				throw new LegacyApiException(400, "not_found");
			}
			// The value collected into `values` above is still the raw body
			// value (e.g. a fractional "207101.9"), not what was just checked.
			// MariaDB's non-strict mode would truncate/round that independently
			// at write time, possibly landing on a different, unvalidated id --
			// so the validated long, not the raw input, is what gets persisted.
			values.set(columns.indexOf("request_type_id"), requestTypeId);
		}

		store.updateFields(id, context.employeeId(), columns, values);
		return store.byIdWithTypeAndEmployeeName(id);
	}

	/** {@code requests/delete.php}: owner-only, pending-only, a hard delete. */
	public void delete(LegacyRequestContext context, long id) {
		Map<String, Object> request = store.byIdOwnedByEmployee(id, context.employeeId());
		if (request == null) {
			throw new LegacyApiException(400, "not_found");
		}
		if (!"pending".equals(request.get("status"))) {
			throw new LegacyApiException(400, "already_decided");
		}
		store.deleteOwnedByEmployee(id, context.employeeId());
	}

	/**
	 * {@code requests/reject.php}. Unlike {@code request_approve()}, the reply
	 * is stored exactly as received -- an empty string stays an empty string,
	 * never normalised to {@code NULL} the way {@code approve} treats one.
	 *
	 * <p>Unlike legacy, {@code approver_id} is written here. Neither
	 * {@code approve.php} nor {@code reject.php} ever populates that column in
	 * PHP -- it is passed into {@code request_approve()} for the notification
	 * only -- but nothing in legacy reads it either, so leaving it null carries
	 * no compatibility benefit and only loses the deciding employee for every
	 * later reader (`one.php`/`list.php`'s own response, and any ETL/report
	 * this wave's own specification already flags {@code approver_id} mapping
	 * as needing resolved). A deliberate correction, not a preserved bug.
	 */
	public void reject(LegacyRequestContext context, String locale, long id, String reply) {
		Map<String, Object> request = store.byIdForCompanyWithType(id, context.companyId());
		if (request == null) {
			throw new LegacyApiException(400, "not_found");
		}
		if (!"pending".equals(request.get("status"))) {
			throw new LegacyApiException(400, "already_decided");
		}

		Long approverEmployeeId = context.employeeId() == 0L ? null : context.employeeId();
		store.updateStatus(id, "rejected", reply, approverEmployeeId);

		String title = messages.translate(locale, "request_rejected", null);
		String body = reply != null && !LegacyValues.phpTrim(reply).isEmpty()
				? reply
				: messages.translate(locale, "request_rejected_msg",
						Map.of("type", LegacyValues.toPhpString(request.get("request_type_name"))));

		notifications.toEmployee(
				context.companyId(), LegacyValues.toPhpLong(request.get("employee_id")), approverEmployeeId,
				"request_rejected", title, body, "request", id);
	}

	/**
	 * {@code requests/approve.php} -> {@code request_approve()}
	 * ({@code request_actions_helper.php:201-249}).
	 *
	 * <p>PHP fetches {@code $request} exactly <b>once</b>, on its single PDO
	 * instance, and reuses that same in-memory row for the balance check, the
	 * transaction and the notification -- it is never re-read, so a status
	 * change racing between the pre-check and the transaction is not defended
	 * against there and is not defended against here either. One connection is
	 * opened for the whole call: the fetch and the balance check run on it in
	 * autocommit mode (matching PHP running them <em>above</em>
	 * {@code beginTransaction()}), then it flips to manual-commit only for the
	 * {@code requests} update and the side effects. A failed rollback skips the
	 * {@code autoCommit} restore in {@code close()}, the same reason
	 * {@code LegacyAttendanceImportService} does -- flipping it on a connection
	 * whose transaction never rolled back would implicitly commit it. The
	 * notification runs after the connection is closed, on the ordinary pooled
	 * {@link LegacyNotifications} -- {@code LegacyAttendanceImportService}'s own
	 * "(7) the notification, after the commit" precedent.
	 */
	public void approve(LegacyRequestContext context, String locale, long id, String reply) {
		String normalizedReply = reply != null && !reply.isEmpty() ? reply : null;
		Long approverEmployeeId = context.employeeId() == 0L ? null : context.employeeId();

		Connection connection = open();
		boolean autoCommit;
		try {
			autoCommit = autoCommitOf(connection);
		} catch (RuntimeException ex) {
			close(connection, true, true);
			throw ex;
		}

		LegacyRequestApprovalStore approvalStore = new LegacyRequestApprovalStore(connection);
		Map<String, Object> request = null;
		boolean rollbackFailed = false;
		try {
			request = approvalStore.forApproval(id, context.companyId());
			if (request == null) {
				throw new LegacyApiException(404, "not_found");
			}
			if (!"pending".equals(request.get("status"))) {
				throw new LegacyApiException(409, "already_decided");
			}

			long employeeId = LegacyValues.toPhpLong(request.get("employee_id"));
			String fromDate = LegacyValues.toPhpString(request.get("from_date"));
			String toDate = LegacyValues.toPhpString(request.get("to_date"));
			int days = inclusiveDayCount(fromDate, toDate, clock.now());
			int year = yearOf(fromDate, clock.now());

			if (!LegacyValues.isPhpEmpty(request.get("deduct_balance"))
					&& insufficientLeaveBalance(approvalStore, employeeId, days, year)) {
				throw new LegacyApiException(422, "insufficient_leave_balance");
			}

			try {
				connection.setAutoCommit(false);
			} catch (SQLException ex) {
				throw new IllegalStateException("beginTransaction", ex);
			}

			try {
				approvalStore.updateStatus(id, "approved", normalizedReply, approverEmployeeId);
				applyApprovalSideEffects(approvalStore, employeeId, fromDate, toDate, days, year,
						request, context.companyId());
				connection.commit();
			} catch (RuntimeException ex) {
				connection.rollback();
				throw ex;
			}
		} catch (SQLException ex) {
			rollbackFailed = true;
			throw new IllegalStateException("rollBack", ex);
		} finally {
			close(connection, autoCommit, !rollbackFailed);
		}

		String title = messages.translate(locale, "request_approved", null);
		String body = normalizedReply != null
				? normalizedReply
				: messages.translate(locale, "request_approved_msg",
						Map.of("type", LegacyValues.toPhpString(request.get("request_type_name"))));
		notifications.toEmployee(
				context.companyId(), LegacyValues.toPhpLong(request.get("employee_id")), approverEmployeeId,
				"request_approved", title, body, "request", id);
	}

	/**
	 * {@code request_apply_approval_side_effects()}
	 * ({@code request_actions_helper.php:176-199}). {@code employeeId}/
	 * {@code fromDate}/{@code toDate}/{@code days}/{@code year} are the same
	 * values {@link #approve} already computed for the balance pre-check --
	 * PHP recomputes {@code $days}/{@code $year} here too (a second
	 * {@code strtotime()} pair on the same strings), which is harmless since
	 * they are pure functions of already-validated input; reusing them here
	 * instead just avoids the redundant computation.
	 */
	private void applyApprovalSideEffects(
			LegacyRequestApprovalStore approvalStore, long employeeId, String fromDate, String toDate,
			int days, int year, Map<String, Object> request, long companyId) {
		if (employeeId <= 0 || fromDate.isEmpty() || toDate.isEmpty()) {
			return;
		}

		if (!LegacyValues.isPhpEmpty(request.get("deduct_balance"))) {
			applyLeaveDeduction(approvalStore, employeeId, companyId, days, year);
		}
		if (!LegacyValues.isPhpEmpty(request.get("add_attendance_exception"))) {
			Long suppliedExceptionTypeId = LegacyValues.isPhpEmpty(request.get("exception_type_id"))
					? null : LegacyValues.toPhpLong(request.get("exception_type_id"));
			applyAttendanceExceptions(approvalStore, employeeId, fromDate, toDate, suppliedExceptionTypeId, companyId);
		}
	}

	/** {@code request_apply_leave_deduction()} ({@code request_actions_helper.php:90-121}). */
	private void applyLeaveDeduction(
			LegacyRequestApprovalStore approvalStore, long employeeId, long companyId, int days, int year) {
		if (approvalStore.leaveBalanceExists(employeeId, year)) {
			approvalStore.incrementUsedDays(employeeId, year, days);
			return;
		}
		double defaultLeaveDays = approvalStore.monthlyLeaveAccrualDefault(companyId);
		approvalStore.insertLeaveBalance(employeeId, year, defaultLeaveDays, days);
	}

	/** {@code request_apply_attendance_exceptions()} ({@code request_actions_helper.php:130-174}). */
	private void applyAttendanceExceptions(
			LegacyRequestApprovalStore approvalStore, long employeeId, String fromDate, String toDate,
			Long suppliedExceptionTypeId, long companyId) {
		long exceptionTypeId = exceptionTypes.resolveForCompany(companyId, suppliedExceptionTypeId);
		if (exceptionTypeId <= 0) {
			return;
		}
		LocalDate start = LocalDate.parse(fromDate);
		LocalDate end = LocalDate.parse(toDate);
		if (end.isBefore(start)) {
			return;
		}
		for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
			String date = day.toString();
			if (approvalStore.attendanceExistsForDay(employeeId, date)) {
				continue;
			}
			approvalStore.insertAttendanceException(employeeId, date, exceptionTypeId);
		}
	}

	/** {@code request_insufficient_leave_balance()} ({@code request_actions_helper.php:74-88}). */
	private static boolean insufficientLeaveBalance(
			LegacyRequestApprovalStore approvalStore, long employeeId, int days, int year) {
		if (!approvalStore.leaveBalanceExists(employeeId, year)) {
			return false;
		}
		LegacyRequestApprovalStore.LeaveBalanceRow row = approvalStore.leaveBalance(employeeId, year);
		double available = row == null ? 0.0 : Math.max(0.0, row.totalDays() - row.usedDays());
		return available < days;
	}

	/**
	 * {@code request_inclusive_day_count()} ({@code request_actions_helper.php:28-37}):
	 * 1 when either bound fails to parse, or when {@code to} precedes {@code from}.
	 */
	private static int inclusiveDayCount(String fromDate, String toDate, LocalDateTime reference) {
		LocalDateTime from = LegacyPhpStrtotime.dateTimeOf(fromDate, reference);
		LocalDateTime to = LegacyPhpStrtotime.dateTimeOf(toDate, reference);
		if (from == null || to == null) {
			return 1;
		}
		long deltaSeconds = Duration.between(from, to).getSeconds();
		if (deltaSeconds < 0) {
			return 1;
		}
		return (int) (deltaSeconds / 86400) + 1;
	}

	/** {@code (int) date('Y', strtotime($from_date))} -- 1970 on an unparseable value, matching PHP's coercion. */
	private static int yearOf(String date, LocalDateTime reference) {
		LocalDateTime parsed = LegacyPhpStrtotime.dateTimeOf(date, reference);
		return parsed == null ? 1970 : parsed.getYear();
	}

	private Connection open() {
		try {
			return dataSource.getConnection();
		} catch (SQLException ex) {
			throw new IllegalStateException("getDB", ex);
		}
	}

	private static boolean autoCommitOf(Connection connection) {
		try {
			return connection.getAutoCommit();
		} catch (SQLException ex) {
			throw new IllegalStateException("getAutoCommit", ex);
		}
	}

	/** {@code restoreAutoCommit} false only after a failed rollback -- see the class javadoc. */
	private static void close(Connection connection, boolean autoCommit, boolean restoreAutoCommit) {
		if (connection == null) {
			return;
		}
		if (restoreAutoCommit) {
			try {
				connection.setAutoCommit(autoCommit);
			} catch (SQLException ignored) { // NOPMD - restoring pool state, never the request's outcome
				// Nothing to do: the connection is about to go back to the pool.
			}
		}
		try {
			connection.close();
		} catch (SQLException ignored) { // NOPMD - same
			// Same.
		}
	}

	/** {@code required($data, [$fields])} -- missing, null and "" fail; "0" passes. */
	private static void required(Map<String, Object> body, String... keys) {
		for (String key : keys) {
			Object value = body == null ? null : body.get(key);
			if (value == null || "".equals(value)) {
				throw new LegacyApiException(400, "field_required", null, Map.of("field", key));
			}
		}
	}

	/**
	 * {@code request_optional_time_value()}
	 * ({@code request_actions_helper.php:9-26}): null propagates from a null
	 * or blank-after-trim input; an unparseable non-blank value is
	 * {@code invalid_input} 400, never silently dropped.
	 */
	private static String optionalTimeValue(Object raw) {
		if (raw == null) {
			return null;
		}
		String trimmed = LegacyValues.phpTrim(LegacyValues.toPhpString(raw));
		if (trimmed.isEmpty()) {
			return null;
		}
		Integer minutes = LegacyShiftTimes.toMinutes(trimmed);
		if (minutes == null) {
			throw new LegacyApiException(400, "invalid_input");
		}
		return "%02d:%02d:00".formatted(minutes / 60, minutes % 60);
	}

	/** {@code !empty($_GET[$key])}. */
	private static String nonEmptyQueryValue(LegacyQueryParameters query, String key) {
		Object value = query.value(key);
		return LegacyValues.isPhpEmpty(value) ? null : LegacyValues.toPhpString(value);
	}

}
