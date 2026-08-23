package com.workin.legacy.schedules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.notifications.LegacyNotifications;
import com.workin.legacy.wire.LegacyApiException;

/**
 * {@code schedules/assign_employee_schedule.php} (Wave 12.6 slice 2).
 *
 * <p>Assign one shift's window to an employee across a list of dates. The
 * endpoint is short and the interesting behaviour is all in what it does
 * <em>not</em> do.
 *
 * <h2>No transaction, and the loop is the write</h2>
 * <p>PHP calls {@code schedule_assign_shift_snapshot()} once per date with no
 * surrounding transaction, so each day is its own autocommitted statement. A
 * failure part-way leaves the earlier days committed and the later ones
 * unwritten, and the caller still sees whatever error the failing day raised.
 * Reproduced: this class opens no transaction and is deliberately not
 * {@code @Transactional}.
 *
 * <h2>A snapshot, not a reference</h2>
 * <p>{@code schedule_row_from_shift()} copies the shift's name and window into
 * {@code employee_schedules} as literal values. The row holds no
 * {@code shift_id}, so later edits to the shift never reach an already-assigned
 * day -- which is the whole point of the "snapshot" in the helper's name.
 *
 * <h2>The date string is never parsed</h2>
 * <p>Each element is cast with PHP's {@code (string)} and bound straight into a
 * {@code DATE NOT NULL} column. Under the legacy {@code sql_mode=''} contract
 * MariaDB coerces rather than refusing, so <b>every</b> value writes a row.
 * Measured against MariaDB 11.8:
 *
 * <table>
 * <caption>What MariaDB stores for a bound {@code schedule_date}</caption>
 * <tr><th>bound</th><th>stored</th></tr>
 * <tr><td>{@code 2026-04-26}</td><td>{@code 2026-04-26}</td></tr>
 * <tr><td>{@code 2026/04/26}</td><td>{@code 2026-04-26}</td></tr>
 * <tr><td>{@code 20260426}</td><td>{@code 2026-04-26}</td></tr>
 * <tr><td>{@code 26-04-26}</td><td>{@code 2026-04-26} (two-digit year)</td></tr>
 * <tr><td>{@code  2026-04-26 } (padded)</td><td>{@code 2026-04-26}</td></tr>
 * <tr><td>{@code 2026-04-26 08:03:00}</td><td>{@code 2026-04-26}, warning 1265</td></tr>
 * <tr><td>{@code 26/04/2026}</td><td><b>{@code 0000-00-00}</b>, warning 1265</td></tr>
 * <tr><td>{@code 2026-13-01}, {@code 2026-04-45}, {@code 2026-02-30}</td><td><b>{@code 0000-00-00}</b>, warning 1265</td></tr>
 * <tr><td>{@code ""}, {@code abc}, {@code 7}, {@code Array}</td><td><b>{@code 0000-00-00}</b>, warning 1265</td></tr>
 * </table>
 *
 * <p>So the day-first format the punch parser accepts is <em>not</em> accepted
 * here, and a client that sends {@code 26/04/2026} silently creates a zero-date
 * row rather than getting an error. That is legacy behaviour, reproduced.
 *
 * <h2>Two casts of the same shift name, only one of them trimmed</h2>
 * <p>The stored {@code name} comes from {@code schedule_row_from_shift()},
 * which trims it and turns an empty result into SQL NULL. The notification body
 * uses {@code (string) ($shift['name'] ?? '')} with <b>no</b> trim. A shift
 * named {@code "   "} therefore stores {@code NULL} and still appends
 * {@code " (   )"} to the notification. Both are reproduced exactly.
 */
@Service
public class LegacyScheduleService {

	private final LegacyScheduleStore store;
	private final LegacyNotifications notifications;

	public LegacyScheduleService(LegacyScheduleStore store, LegacyNotifications notifications) {
		this.store = store;
		this.notifications = notifications;
	}

	/** The notification title and body, resolved by the controller because PHP resolves them with {@code t()}. */
	public record NotificationText(String title, String body) {
	}

	/**
	 * The endpoint body, in PHP's order.
	 *
	 * @param notificationText the translated title and the <em>base</em> body;
	 *        the shift-name suffix is appended here, because the concatenation
	 *        is the endpoint's rather than the catalog's
	 */
	public void assign(
			LegacyRequestContext context, Map<String, Object> body, NotificationText notificationText) {
		required(body, "employee_id");
		required(body, "shift_id");
		required(body, "dates");

		long targetEmployeeId = LegacyValues.toPhpLong(body.get("employee_id"));
		long shiftId = LegacyValues.toPhpLong(body.get("shift_id"));
		List<Object> dates = toArray(body.get("dates"));

		if (!store.employeeExistsInCompany(context.companyId(), targetEmployeeId)) {
			throw new LegacyApiException(404, "employee_not_found");
		}
		Map<String, Object> shift = store.shiftForCompany(context.companyId(), shiftId);
		if (shift == null) {
			throw new LegacyApiException(404, "shift_not_found");
		}

		// schedule_row_from_shift($shift, null): the exception_note branch is
		// unreachable from this endpoint, which always passes null.
		String rawName = LegacyValues.toPhpString(shift.get("name") == null ? "" : shift.get("name"));
		String storedName = LegacyValues.phpTrim(rawName);
		String name = storedName.isEmpty() ? null : storedName;
		String startTime = text(shift.get("start_time"));
		String endTime = text(shift.get("end_time"));

		for (Object date : dates) {
			store.upsertDay(targetEmployeeId, LegacyValues.toPhpString(date), name, startTime, endTime);
		}

		// `(int) ($auth['employee_id'] ?? 0) ?: null` -- zero becomes null.
		Long fromEmployeeId = context.employeeId() == 0L ? null : context.employeeId();
		notifications.toEmployee(
				context.companyId(), targetEmployeeId, fromEmployeeId, "schedule_assigned",
				notificationText.title(),
				notificationText.body() + (rawName.isEmpty() ? "" : " (" + rawName + ")"),
				"schedule", shiftId);
	}

	/**
	 * {@code required($body, [...])}: absent or JSON null fails, and
	 * <b>everything else passes</b> -- including an empty array and an empty
	 * object, both of which then produce zero writes and a successful response.
	 * Measured, because "required" reads as if it would reject them.
	 */
	private static void required(Map<String, Object> body, String field) {
		Object value = body.get(field);
		if (value == null || "".equals(value)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", field));
		}
	}

	/**
	 * PHP's {@code (array)} cast over a {@code json_decode(..., true)} value,
	 * followed by {@code foreach} -- so only the values matter.
	 *
	 * <p>A JSON array yields its elements, a JSON object yields its values in
	 * insertion order, and a scalar becomes a one-element list. An element that
	 * is itself an array survives to {@code (string)}, which makes it the
	 * literal {@code "Array"} -- and {@code "Array"} is one of the values
	 * MariaDB stores as {@code 0000-00-00}.
	 */
	private static List<Object> toArray(Object value) {
		if (value instanceof Collection<?> collection) {
			return new ArrayList<>(collection);
		}
		if (value instanceof Map<?, ?> map) {
			return new ArrayList<>(map.values());
		}
		List<Object> single = new ArrayList<>(1);
		single.add(value);
		return single;
	}

	/** A shift column read back as its lexical value, or null. */
	private static String text(Object value) {
		return value == null ? null : LegacyValues.toPhpString(value);
	}

}
