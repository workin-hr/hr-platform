package com.workin.legacy.workforce;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyPagination;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.employees.LegacyEmployeeStore;
import com.workin.legacy.notifications.LegacyNotifications;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyMessages;

/**
 * {@code hr-legacy/apis/api/requests/*.php} (Wave 12.7, slice 1).
 *
 * <h2>{@code approve.php} is a separate slice</h2>
 * <p>{@code request_approve()} ({@code request_actions_helper.php:201-249})
 * writes {@code requests}, {@code leave_balance} and {@code attendance} and
 * sends a notification, all inside one PDO transaction. Porting that
 * atomically needs the same explicit-{@code Connection}-sharing shape
 * {@link com.workin.legacy.attendance.spreadsheet.LegacyAttendanceImportService}
 * uses across stores, which this slice does not yet build. The other six
 * endpoints -- {@code list}, {@code one}, {@code create}, {@code update},
 * {@code delete}, {@code reject} -- touch only {@code requests} itself (or,
 * for {@code reject}, {@code requests} plus one notification insert that
 * legacy does not require to share the request's transaction), so they carry
 * no such dependency and are delivered here.
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
 */
@Service
public class LegacyRequestService {

	private static final Set<String> VALID_STATUSES = Set.of("pending", "approved", "rejected");

	private final LegacyRequestStore store;
	private final LegacyRequestTypeStore requestTypeStore;
	private final LegacyEmployeeStore employeeStore;
	private final LegacyNotifications notifications;
	private final LegacyMessages messages;

	public LegacyRequestService(
			LegacyRequestStore store, LegacyRequestTypeStore requestTypeStore,
			LegacyEmployeeStore employeeStore, LegacyNotifications notifications, LegacyMessages messages) {
		this.store = store;
		this.requestTypeStore = requestTypeStore;
		this.employeeStore = employeeStore;
		this.notifications = notifications;
		this.messages = messages;
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

		String dateFrom = nonEmptyQueryValue(query, "date_from");
		String dateTo = nonEmptyQueryValue(query, "date_to");
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
	 */
	public void reject(LegacyRequestContext context, String locale, long id, String reply) {
		Map<String, Object> request = store.byIdForCompanyWithType(id, context.companyId());
		if (request == null) {
			throw new LegacyApiException(400, "not_found");
		}
		if (!"pending".equals(request.get("status"))) {
			throw new LegacyApiException(400, "already_decided");
		}

		store.updateStatus(id, "rejected", reply);

		Long approverEmployeeId = context.employeeId() == 0L ? null : context.employeeId();
		String title = messages.translate(locale, "request_rejected", null);
		String body = reply != null && !LegacyValues.phpTrim(reply).isEmpty()
				? reply
				: messages.translate(locale, "request_rejected_msg",
						Map.of("type", LegacyValues.toPhpString(request.get("request_type_name"))));

		notifications.toEmployee(
				context.companyId(), LegacyValues.toPhpLong(request.get("employee_id")), approverEmployeeId,
				"request_rejected", title, body, "request", id);
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
