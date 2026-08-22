package com.workin.legacy.workforce;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyValues;
import com.workin.legacy.notifications.LegacyNotifications;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyMessages;

/**
 * {@code hr-legacy/apis/api/shifts/*.php}, statement for statement.
 *
 * <p>Everything surprising in this module is legacy's, and each of the four is
 * load-bearing enough to name:
 *
 * <ul>
 * <li>{@code update.php} validates the <em>merged</em> window on every PUT, so
 *     renaming a shift re-validates times the caller never sent -- and a shift
 *     whose stored times are outside the validator's range cannot be renamed
 *     at all.</li>
 * <li>{@code update.php} and {@code delete.php} look a shift up by id and
 *     company only, never {@code is_active}, so a soft-deleted shift stays
 *     updatable and deletable.</li>
 * <li>The {@code COALESCE} update means {@code days_off} can never be cleared:
 *     an explicit {@code null} and an absent key produce the same bind.</li>
 * <li>The broadcast fires on <em>presence</em> of a non-null time, never on a
 *     change (D-089). Its title and body come from {@code t()}, so they are
 *     rendered in the <em>request's</em> locale, not a fixed one -- the
 *     caller's language decides what every recipient reads.</li>
 * </ul>
 */
@Service
public class LegacyShiftService {

	private final LegacyShiftStore store;
	private final LegacyNotifications notifications;
	private final LegacyMessages messages;

	public LegacyShiftService(
			LegacyShiftStore store, LegacyNotifications notifications, LegacyMessages messages) {
		this.store = store;
		this.notifications = notifications;
		this.messages = messages;
	}

	/** {@code shifts/list.php}. */
	public List<Map<String, Object>> list(long companyId) {
		return store.list(companyId);
	}

	/**
	 * {@code shifts/one.php}. The miss answers {@code forbidden} at <b>404</b>
	 * -- legacy's own pairing, and not a mistake in this port: the key is
	 * {@code LangKey::FORBIDDEN} while the status is 404, so the body reads
	 * "forbidden" with a not-found status. Same shape D-060 settled elsewhere.
	 */
	public Map<String, Object> one(long companyId, long id) {
		Map<String, Object> shift = store.activeById(companyId, id);
		if (shift == null) {
			throw new LegacyApiException(404, "forbidden");
		}
		return shift;
	}

	/**
	 * {@code shifts/create.php}.
	 *
	 * <p>The order matters and is PHP's: {@code required()} first, then the
	 * window assertion, then the INSERT. The assertion runs on
	 * {@code (string)}-cast copies while the INSERT binds
	 * {@code $request_body[...]} itself -- so the cast is validation-only and
	 * must not reach the statement. A JSON number {@code 900} would be
	 * validated as {@code "900"} (and rejected), but a value that <em>does</em>
	 * pass reaches the driver as the original type.
	 */
	public Map<String, Object> create(long companyId, Map<String, Object> body) {
		required(body, "name", "start_time", "end_time");

		LegacyShiftTimes.assertDailyWindowValid(
				LegacyValues.toPhpString(body.get("start_time")),
				LegacyValues.toPhpString(body.get("end_time")));

		long id = store.insert(
				companyId,
				body.get("name"),
				body.get("start_time"),
				body.get("end_time"),
				body.get("days_off"));

		return store.byId(id);
	}

	/**
	 * {@code shifts/update.php}.
	 *
	 * <p>Reproduced in PHP's own order, because the order is observable:
	 *
	 * <ol>
	 * <li>the id + company lookup, without {@code is_active} -- miss is
	 *     {@code shift_not_found} 404;</li>
	 * <li>{@code body()}, then the merge {@code $body[x] ?? $shift[x]}, which
	 *     treats an explicit {@code null} exactly like an absent key;</li>
	 * <li>the {@code is_string} guard on both merged values -- a non-string
	 *     that survived the merge is {@code invalid_input} 400 <em>before</em>
	 *     any time parsing;</li>
	 * <li>the window assertion on the merged pair, unconditionally;</li>
	 * <li>the {@code COALESCE} update, the re-read, and only then the
	 *     broadcast.</li>
	 * </ol>
	 */
	public Map<String, Object> update(
			long companyId, Long actingEmployeeId, String locale, long id, Map<String, Object> body) {
		Map<String, Object> shift = store.byIdForCompany(companyId, id);
		if (shift == null) {
			throw new LegacyApiException(404, "shift_not_found");
		}

		// `$body[x] ?? $shift[x]`: null-coalescing, so an explicit JSON null
		// falls through to the stored value just as a missing key does.
		Object mergedStart = supplied(body, "start_time") == null ? shift.get("start_time") : body.get("start_time");
		Object mergedEnd = supplied(body, "end_time") == null ? shift.get("end_time") : body.get("end_time");

		// `if (!is_string($mergedStart) || !is_string($mergedEnd))` -- this is a
		// type test, not a cast, so a JSON number here fails rather than being
		// stringified into something the validator would accept.
		if (!(mergedStart instanceof String start) || !(mergedEnd instanceof String end)) {
			throw new LegacyApiException(400, "invalid_input");
		}

		// Unconditional: a name-only or days_off-only PUT still validates the
		// merged window, so a shift whose stored times are out of range cannot
		// be renamed until its times are fixed in the same request.
		LegacyShiftTimes.assertDailyWindowValid(start, end);

		store.update(
				id,
				supplied(body, "name"),
				supplied(body, "start_time"),
				supplied(body, "end_time"),
				supplied(body, "days_off"));

		Map<String, Object> updated = store.byId(id);

		// D-089: `($body['start_time'] ?? null) !== null || ($body['end_time'] ?? null) !== null`.
		// Presence of a non-null value, never a comparison against the stored
		// one -- so re-sending an unchanged time broadcasts to the whole
		// company, and an explicit null broadcasts nothing.
		boolean timeChanged = supplied(body, "start_time") != null || supplied(body, "end_time") != null;
		if (timeChanged && updated != null) {
			notifications.broadcastToCompanyEmployees(
					companyId,
					actingEmployeeId,
					"shift_time_changed",
					messages.translate(locale, "notif_shift_time_changed_title", null),
					messages.translate(locale, "notif_shift_time_changed_body", Map.of(
							"shift", LegacyValues.toPhpString(updated.get("name")),
							"start", LegacyValues.toPhpString(updated.get("start_time")),
							"end", LegacyValues.toPhpString(updated.get("end_time")))),
					"shift",
					id);
		}

		return updated;
	}

	/**
	 * {@code shifts/delete.php}: the same unscoped-by-{@code is_active} lookup
	 * as {@code update}, then {@code is_active = 0}. Deleting an
	 * already-deleted shift succeeds and reports success, because the lookup
	 * still finds it.
	 */
	public void delete(long companyId, long id) {
		if (store.byIdForCompany(companyId, id) == null) {
			throw new LegacyApiException(404, "shift_not_found");
		}
		store.softDelete(id);
	}

	/**
	 * {@code $body[$key] ?? null}: absent and explicitly null are the same
	 * thing to PHP's null-coalescing operator, which is what makes an explicit
	 * {@code "days_off": null} unable to clear the column and an explicit
	 * {@code "start_time": null} unable to trigger the broadcast.
	 */
	private static Object supplied(Map<String, Object> body, String key) {
		return body == null ? null : body.get(key);
	}

	/**
	 * {@code required($request_body, [...])} ({@code functions.php:617-623}):
	 * missing, {@code null} and the exact empty string fail; {@code "0"}
	 * passes. The failure carries the field as a {@code {field}} placeholder in
	 * the message, so the body has no {@code data} key.
	 */
	private static void required(Map<String, Object> body, String... keys) {
		for (String key : keys) {
			Object value = body == null ? null : body.get(key);
			if (value == null || "".equals(value)) {
				throw new LegacyApiException(400, "field_required", null, Map.of("field", key));
			}
		}
	}

}
