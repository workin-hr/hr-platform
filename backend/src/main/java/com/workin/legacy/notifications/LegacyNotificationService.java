package com.workin.legacy.notifications;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyPagination;
import com.workin.legacy.LegacyPublicRow;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.wire.LegacyApiException;

/** The six {@code apis/api/notifications/*.php} endpoints. */
@Service
public class LegacyNotificationService {

	/** {@code NotificationTypeEnum::MANUAL}, the only type {@code send.php} writes. */
	private static final String MANUAL = "manual";

	private final LegacyNotificationStore store;
	private final LegacyNotifications notifications;

	public LegacyNotificationService(LegacyNotificationStore store, LegacyNotifications notifications) {
		this.store = store;
		this.notifications = notifications;
	}

	/** A page of rows plus its {@code pagination_meta()}. */
	public record Page(List<Map<String, Object>> rows, Map<String, Object> meta) {
	}

	/** {@code list.php}. */
	public Page list(LegacyRequestContext context, LegacyQueryParameters query) {
		LegacyNotificationInbox inbox = LegacyNotificationInbox.of(context);
		LegacyPagination.Params pagination = LegacyPagination.params(query);
		long total = store.count(inbox, false);
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Map<String, Object> row : store.list(inbox, pagination)) {
			rows.add(LegacyPublicRow.of(row));
		}
		return new Page(rows, LegacyPagination.meta(total, pagination));
	}

	/** {@code unread_count.php}: {@code ok(UNREAD_COUNT, ['count' => n])}. */
	public Map<String, Object> unreadCount(LegacyRequestContext context) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("count", store.count(LegacyNotificationInbox.of(context), true));
		return data;
	}

	/**
	 * {@code one.php} -- a GET that <b>writes</b>: reading a notification marks
	 * it read.
	 *
	 * <p>The update is skipped when the row is already read, and the returned
	 * body carries {@code is_read} as the integer {@code 1} that PHP assigns
	 * in-memory rather than a re-read of the column, so the response is
	 * identical whether or not the update ran.
	 */
	public Map<String, Object> oneAndMarkRead(LegacyRequestContext context, long id) {
		LegacyNotificationInbox inbox = LegacyNotificationInbox.of(context);
		Map<String, Object> row = store.byIdInInbox(inbox, id);
		if (row == null) {
			throw new LegacyApiException(404, "notification_not_found");
		}
		if (LegacyValues.toPhpLong(row.get("is_read")) == 0) {
			store.markRead(id);
			row.put("is_read", 1L);
		}
		return LegacyPublicRow.of(row);
	}

	/**
	 * {@code mark_read.php}. A falsy id -- absent, {@code 0}, or anything that
	 * casts to {@code 0} such as {@code ?id=abc} -- takes the <b>all</b>
	 * branch, exactly as {@code if ($id)} does in PHP.
	 */
	public void markRead(LegacyRequestContext context, Long id) {
		LegacyNotificationInbox inbox = LegacyNotificationInbox.of(context);
		if (id != null && id != 0) {
			requireOwned(inbox, id);
			store.markRead(id);
			return;
		}
		store.markAllRead(inbox);
	}

	/**
	 * {@code delete.php}, with the same falsy-id rule as
	 * {@link #markRead(LegacyRequestContext, Long)} -- and it is far more
	 * consequential here: {@code DELETE ...?id=abc} empties the caller's whole
	 * inbox rather than answering 400. That is legacy's behaviour and D-058
	 * puts the burden of proof on changing it, so it is ported as-is and
	 * asserted in the tests so nobody "fixes" it by accident.
	 */
	public void delete(LegacyRequestContext context, Long id) {
		LegacyNotificationInbox inbox = LegacyNotificationInbox.of(context);
		if (id != null && id != 0) {
			requireOwned(inbox, id);
			store.deleteById(id);
			return;
		}
		store.deleteAll(inbox);
	}

	/** {@code send.php}'s two outcomes: a broadcast count, or the created row. */
	public sealed interface SendResult {
		/** {@code ok(BROADCAST_SENT, ['count' => n])} -- 200, not 201. */
		record Broadcast(int count) implements SendResult {
		}

		/** {@code ok(NOTIFICATION_SENT, public_row($row), 201)}. */
		record Sent(Map<String, Object> row) implements SendResult {
		}
	}

	/**
	 * {@code send.php}.
	 *
	 * <p>{@code !empty($body[TO_ALL_COMPANY])} decides the branch, and
	 * {@code empty()} is not {@code isset()}: {@code to_all_company} of
	 * {@code false}, {@code 0}, {@code "0"}, {@code ""} or {@code []} all take
	 * the single-recipient path. Only when it is truthy does the broadcast run
	 * -- and then the function returns without ever looking at
	 * {@code to_employee_id}, so a request carrying both broadcasts.
	 *
	 * <p>{@code from_employee_id} is the acting employee. A {@code type=company}
	 * session has none, and {@code notification_normalize_from()} turns the
	 * resulting {@code 0} into SQL NULL rather than pointing at employee 0.
	 */
	public SendResult send(LegacyRequestContext context, Map<String, Object> body) {
		required(body, "title");
		String title = LegacyValues.toPhpString(body.get("title"));
		String messageBody = body.get("body") == null ? null : LegacyValues.toPhpString(body.get("body"));
		Long from = context.employeeId();

		if (!LegacyValues.isPhpEmpty(body.get("to_all_company"))) {
			int count = notifications.broadcastToCompanyEmployees(
					context.companyId(), from, MANUAL, title, messageBody, null, null);
			return new SendResult.Broadcast(count);
		}

		required(body, "to_employee_id");
		long toEmployeeId = LegacyValues.toPhpLong(body.get("to_employee_id"));
		if (!store.employeeExistsInCompany(toEmployeeId, context.companyId())) {
			throw new LegacyApiException(404, "employee_not_found");
		}

		long notificationId = notifications.toEmployee(
				context.companyId(), toEmployeeId, from, MANUAL, title, messageBody);
		return new SendResult.Sent(LegacyPublicRow.of(store.byId(notificationId)));
	}

	private void requireOwned(LegacyNotificationInbox inbox, long id) {
		if (!store.existsInInbox(inbox, id)) {
			throw new LegacyApiException(404, "notification_not_found");
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
}
