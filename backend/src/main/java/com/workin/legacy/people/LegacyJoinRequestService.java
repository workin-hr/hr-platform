package com.workin.legacy.people;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyPagination;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.employees.LegacyEmployeeStore;
import com.workin.legacy.notifications.LegacyNotifications;
import com.workin.legacy.wire.LegacyMessages;
import com.workin.legacy.wire.LegacyApiException;

/**
 * {@code company_join_requests/*.php} -- accepting or rejecting provisional
 * employee rows.
 *
 * <h2>Rejection deletes the row; acceptance only flips two columns</h2>
 * <p>{@code accept.php} sets {@code join_request_status='accepted'} and
 * {@code is_active=1}. {@code reject.php} <b>deletes the employee row
 * entirely</b>, so the phone number becomes available again for the same or
 * another company. The two are not symmetric operations, and a rejection is not
 * recoverable.
 *
 * <p>{@code reject.php} also notifies <em>before</em> it deletes, which is the
 * only order that works: the notification references the employee it is about
 * to remove.
 */
@Service
public class LegacyJoinRequestService {

	/** Join requests are only ever employee-role rows. */
	private static final String EMPLOYEE_ROLE = "employee";

	private final LegacyPeopleStore store;
	private final LegacyEmployeeStore employeeStore;
	private final LegacyNotifications notifications;
	private final LegacyMessages messages;

	public LegacyJoinRequestService(
			LegacyPeopleStore store, LegacyEmployeeStore employeeStore,
			LegacyNotifications notifications, LegacyMessages messages) {
		this.store = store;
		this.employeeStore = employeeStore;
		this.notifications = notifications;
		this.messages = messages;
	}

	public record Page(List<Map<String, Object>> rows, Map<String, Object> meta) {
	}

	/**
	 * {@code list.php}.
	 *
	 * <p>{@code status} defaults to {@code pending} and is applied only when it
	 * is one of the three known values -- so an unrecognised value returns
	 * <b>every</b> status rather than none, and there is no {@code all} escape
	 * hatch here, unlike {@code complaints}.
	 */
	public Page list(long companyId, LegacyQueryParameters query) {
		List<String> where = new ArrayList<>(List.of("e.company_id=?", "e.role=?"));
		List<Object> binds = new ArrayList<>(List.of(companyId, EMPLOYEE_ROLE));

		String status = query.value("join_request_status") == null
				? "pending" : LegacyValues.toPhpString(query.value("join_request_status"));
		if (List.of("pending", "accepted", "rejected").contains(status)) {
			where.add("e.join_request_status=?");
			binds.add(status);
		}

		String search = LegacyPagination.searchQueryParam(query);
		if (search != null) {
			where.add("(TRIM(CONCAT(COALESCE(e.first_name,''),' ',COALESCE(e.last_name,'')))"
					+ " LIKE ? OR e.phone LIKE ? OR CAST(e.id AS CHAR) LIKE ?)");
			String like = "%" + search + "%";
			binds.add(like);
			binds.add(like);
			binds.add(like);
		}

		LegacyPagination.Params pagination = LegacyPagination.params(query);
		long total = store.countJoinRequests(where, binds);
		return new Page(
				store.joinRequests(where, binds, pagination.limit(), pagination.offset()),
				LegacyPagination.meta(total, pagination));
	}

	/**
	 * {@code accept.php}.
	 *
	 * <p>Accepts a row in <b>any</b> status, not only a pending one -- so
	 * accepting an already-accepted request succeeds and re-notifies. Only
	 * {@code reject.php} checks pendingness.
	 */
	public Map<String, Object> accept(long companyId, long actorEmployeeId, long id, String locale) {
		if (store.employeeRow(id, companyId, EMPLOYEE_ROLE) == null) {
			throw new LegacyApiException(404, "not_found");
		}
		store.acceptJoinRequest(id);

		Map<String, Object> updated = store.employeeById(id);
		if (updated != null) {
			employeeStore.attachLatestSalaryContract(updated);
		}

		notifications.toEmployee(companyId, id,
				actorEmployeeId > 0 ? actorEmployeeId : null,
				"employee_join_accepted",
				message(locale, "notif_employee_join_accepted_title"),
				message(locale, "notif_employee_join_accepted_body"));
		return updated;
	}

	/**
	 * {@code reject.php} -- notify, then delete.
	 *
	 * <p>Pendingness is decided by {@code join_request_is_pending()}, which
	 * treats an <b>empty</b> status as pending as well as the literal
	 * {@code 'pending'}, after lowercasing and trimming. So a row with a blank
	 * status is rejectable while an accepted one is a 404.
	 */
	public Map<String, Object> reject(long companyId, long id, String locale) {
		Map<String, Object> pending = store.employeeRow(id, companyId, EMPLOYEE_ROLE);
		if (pending == null || !isPending(pending)) {
			throw new LegacyApiException(404, "not_found");
		}

		notifications.toEmployee(companyId, id, null,
				"join_request_rejected",
				message(locale, "join_request_rejected"),
				message(locale, "notif_join_request_rejected_body"),
				"employee", id);

		store.deleteEmployee(id, companyId);
		return pending;
	}

	/** {@code join_request_is_pending()}. */
	static boolean isPending(Map<String, Object> row) {
		Object raw = row.get("join_request_status");
		String status = raw == null ? "pending" : LegacyValues.toPhpString(raw);
		status = status.trim().toLowerCase(Locale.ROOT);
		return status.isEmpty() || "pending".equals(status);
	}

	private String message(String locale, String key) {
		return messages.translate(locale, key, null);
	}
}
