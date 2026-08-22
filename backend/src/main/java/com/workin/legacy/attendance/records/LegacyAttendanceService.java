package com.workin.legacy.attendance.records;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.attendance.LegacyExceptionTypeService;
import com.workin.legacy.LegacyPhpDateYear;
import com.workin.legacy.LegacyPhpStrtotime;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.wire.LegacyApiException;

/**
 * `hr-legacy/apis/api/attendance/{one,create,update,delete,delete_range}.php`
 * -- Wave 12.6 slices 1a-i and 1a-ii.
 *
 * <p>None of the five reaches `schedule_helper`, `company_settings` or
 * `payroll_calculation` (Wave 12.6 discovery, section G.1), which is why they
 * land ahead of D-091. No settings or payroll code appears here.
 *
 * <h2>The two behaviours most likely to be "tidied"</h2>
 * <ul>
 * <li>`update.php` <b>deletes</b> the row when both punches are cleared and no
 * exception remains, and still answers `attendance_record_updated`. It is an
 * update endpoint that can destroy the resource.</li>
 * <li>`create.php` validates the exception type against the company and its
 * active flag; `update.php` does <b>not</b>, and D-095 adds only an ownership
 * preflight there, never an active check. The asymmetry is legacy's.</li>
 * </ul>
 */
@Service
public class LegacyAttendanceService {

	/**
	 * `create.php:96` -- a hard-coded Arabic string passed as
	 * `Response::REASON`, kept byte-for-byte so the port matches the source.
	 *
	 * <p><b>It is not observable on the wire.</b> `fail()` takes that map as
	 * `$replace`, a message-placeholder map, and the `invalid_input` catalog
	 * entry has no `{reason}` token -- `en.php:303` is plain `Invalid input`
	 * and `ar.php:306` its Arabic equivalent. Other keys do carry `{reason}`
	 * (`company_rejected`, `employees_excel_invalid_template`); this one does
	 * not. So a caller receives a bare `Invalid input` at 422 and never sees
	 * this sentence.
	 *
	 * <p>The constant stays because the source has it and a reader comparing
	 * the two should find it; the assertion that matters is on the message a
	 * client actually gets.
	 */
	static final String DUPLICATE_PUNCH_REASON =
			"\u0644\u0627 \u064a\u0645\u0643\u0646 \u062a\u0633\u062c\u064a\u0644 "
					+ "\u0628\u0635\u0645\u062a\u064a\u0646 \u0645\u062a\u062a\u0627\u0644\u064a\u062a\u064a\u0646 "
					+ "\u062e\u0644\u0627\u0644 \u0627\u0642\u0644 \u0645\u0646 \u0633\u0627\u0639\u062a\u064a\u0646";

	private final LegacyAttendanceStore store;
	private final LegacyClock clock;
	private final LegacyExceptionTypeService exceptionTypes;

	public LegacyAttendanceService(
			LegacyAttendanceStore store, LegacyClock clock, LegacyExceptionTypeService exceptionTypes) {
		this.store = store;
		this.clock = clock;
		this.exceptionTypes = exceptionTypes;
	}

	/**
	 * `one.php`.
	 *
	 * <p>The ordering is load-bearing and is <b>not</b> improved: the row is
	 * resolved first, and only then is an EMPLOYEE checked against it. So a
	 * missing or foreign id answers `not_found` 404 for every role, while an
	 * employee asking for a same-company colleague's row answers `forbidden`
	 * 403 -- which does disclose that the row exists. That disclosure is
	 * legacy's choice and reproducing it is the point.
	 */
	public Map<String, Object> one(LegacyRequestContext context, long id) {
		Map<String, Object> row = store.recordFull(context.companyId(), id);
		if (row == null) {
			throw new LegacyApiException(404, "not_found");
		}
		if (context.role() == LegacyEmployee.Role.EMPLOYEE
				&& LegacyValues.toPhpLong(row.get("employee_id")) != context.employeeId()) {
			throw new LegacyApiException(403, "forbidden");
		}
		return row;
	}

	/**
	 * `create.php`, in PHP's statement order.
	 *
	 * <h2>The parser decides; it does not write</h2>
	 * <p>`strtotime()` is used only to classify a real punch. The values
	 * stored are the caller's raw strings, coerced by MariaDB exactly as PDO
	 * hands them over -- measured under `sql_mode=''`: `'2026-01-15'` becomes
	 * `2026-01-15 00:00:00`, while `'now'`, `'1990'`, `'0830'` and
	 * `'2026-02-30'` all become `0000-00-00 00:00:00` even though the parser
	 * resolves each to a real instant. Substituting the parsed value would
	 * silently repair rows legacy stores as the zero date.
	 *
	 * <p>The one deliberate normalization is the exception-only branch, which
	 * PHP itself writes as a formatted midnight anchor.
	 */
	public Map<String, Object> create(long companyId, Map<String, Object> body) {
		required(body, "employee_id");

		long employeeId = LegacyValues.toPhpLong(value(body, "employee_id"));
		Object rawCheckIn = value(body, "check_in");
		Object rawCheckOut = value(body, "check_out");

		// `isset() && !== '' && !== null`, then `(int)`, then a non-positive
		// result collapses back to null.
		Long exceptionTypeId = null;
		Object rawException = value(body, "exception_type_id");
		if (rawException != null && !"".equals(rawException)) {
			long cast = LegacyValues.toPhpLong(rawException);
			exceptionTypeId = cast > 0 ? cast : null;
		}

		boolean hasCheckIn = rawCheckIn != null
				&& !LegacyValues.phpTrim(LegacyValues.toPhpString(rawCheckIn)).isEmpty();
		boolean hasCheckOut = rawCheckOut != null
				&& !LegacyValues.phpTrim(LegacyValues.toPhpString(rawCheckOut)).isEmpty();
		boolean hasException = exceptionTypeId != null;

		if (!hasCheckIn && !hasCheckOut && !hasException) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "check_in"));
		}

		if (!store.employeeExistsInCompany(companyId, employeeId)) {
			throw new LegacyApiException(400, "invalid_employee");
		}

		if (hasException) {
			// create.php's own validator: same company AND active. update.php
			// has no equivalent -- see D-095 -- and the two are not harmonised.
			exceptionTypeId = exceptionTypes.validateIdForCompany(companyId, exceptionTypeId);
			if (exceptionTypeId == null) {
				throw new LegacyApiException(422, "invalid_input");
			}
		}

		// A check_in whose resolved time is exactly midnight is NOT a real
		// punch: that is how legacy separates an exception-only day from a
		// genuine arrival, and it means a real 00:00 punch cannot be recorded
		// through this endpoint.
		boolean isRealPunchIn = false;
		if (hasCheckIn) {
			LocalDateTime parsed = LegacyPhpStrtotime.dateTimeOf(
					LegacyValues.toPhpString(rawCheckIn), clock.now());
			isRealPunchIn = parsed != null && !parsed.toLocalTime().equals(LocalTime.MIDNIGHT);
		}
		// A nonblank check_out counts as a real punch WITHOUT being parsed at
		// all -- the asymmetry is legacy's and is preserved.
		boolean hasRealPunch = isRealPunchIn || hasCheckOut;

		String checkIn;
		String checkOut;
		if (hasException && !hasRealPunch) {
			String anchorRaw = hasCheckIn
					? LegacyValues.toPhpString(rawCheckIn)
					: clock.today().toString();
			LocalDateTime anchor = LegacyPhpStrtotime.dateTimeOf(anchorRaw, clock.now());
			if (anchor == null) {
				throw new LegacyApiException(422, "invalid_input");
			}
			checkIn = anchor.toLocalDate() + " 00:00:00";
			checkOut = null;
		} else {
			if (!hasCheckIn) {
				throw new LegacyApiException(400, "field_required", null, Map.of("field", "check_in"));
			}
			// Raw, not parsed. See the class note above.
			checkIn = LegacyValues.toPhpString(rawCheckIn);
			checkOut = hasCheckOut ? LegacyValues.toPhpString(rawCheckOut) : null;

			String lastCheckIn = store.latestCheckIn(employeeId);
			if (lastCheckIn != null) {
				Long minutes = store.minutesBetween(lastCheckIn, checkIn);
				// `$minutes = (int) db_value(...)` -- and `(int) null` is 0 in
				// PHP, not "unknown". Measured on MariaDB 11.8: TIMESTAMPDIFF
				// returns SQL NULL when the candidate does not coerce, for
				// every odd raw value reachable here -- 'now', '1990', '0060',
				// '0830', 'oops'. So PHP sees 0, which is inside [0, 120), and
				// REJECTS. Treating null as "skip the check" would let those
				// through where legacy refuses them.
				long sinceLast = minutes == null ? 0L : minutes;
				// A negative gap passes, and exactly 120 passes. Only [0, 120)
				// is refused.
				if (sinceLast >= 0 && sinceLast < 120) {
					throw new LegacyApiException(
							422, "invalid_input", null, Map.of("reason", DUPLICATE_PUNCH_REASON));
				}
			}
		}

		// `$body[METHOD] ?? APP` -- absent means 'app'. The column is an enum
		// and MariaDB owns the coercion; no Java enum validation is added,
		// because PHP performs none.
		Object method = value(body, "method") == null
				? "app"
				: value(body, "method");

		long id = store.insert(employeeId, checkIn, checkOut, method, exceptionTypeId);
		return requirePublicRow(store.recordFull(companyId, id));
	}

	/** The outcome of `update.php` -- it can delete the row it was asked to update. */
	public record UpdateOutcome(boolean deleted, Map<String, Object> row) {
	}

	/**
	 * `update.php`, in PHP's statement order.
	 *
	 * <p>There is no nothing-to-update branch: an empty body rewrites the
	 * stored values onto themselves and answers 200.
	 *
	 * <p>D-095's ownership preflight runs <b>immediately before the UPDATE</b>,
	 * after every legacy branch above it has had its chance -- the hard-delete
	 * path, the cleared-punch conversion, the required-check_in guard and the
	 * final midnight normalization. Putting it earlier would replace legacy
	 * failures that must still happen first.
	 */
	public UpdateOutcome update(
			long companyId, long id, Supplier<Map<String, Object>> bodySupplier) {
		// The scoped row is read BEFORE the body, because PHP does:
		//   attendance_record_full(...) -> 404 -> body()
		// A missing or foreign id therefore answers 404 even when the body
		// is a scalar that body() would choke on. Reading the body first
		// would turn that 404 into a 500.
		Map<String, Object> existing = store.recordFull(companyId, id);
		if (existing == null) {
			throw new LegacyApiException(404, "not_found");
		}

		Map<String, Object> body = bodySupplier.get();

		boolean clearCheckIn = !LegacyValues.isPhpEmpty(value(body, "clear_check_in"))
				|| (containsKey(body, "check_in") && isNullOrEmptyString(value(body, "check_in")));
		boolean clearCheckOut = !LegacyValues.isPhpEmpty(value(body, "clear_check_out"))
				|| (containsKey(body, "check_out") && isNullOrEmptyString(value(body, "check_out")));
		boolean clearException = !LegacyValues.isPhpEmpty(value(body, "clear_exception_type"))
				|| (containsKey(body, "exception_type_id")
						&& isNullOrEmptyString(value(body, "exception_type_id")));

		String newCheckIn = clearCheckIn
				? null
				: (containsKey(body, "check_in")
						? LegacyValues.toPhpString(value(body, "check_in"))
						: LegacyValues.toPhpString(existing.get("check_in")));

		String newCheckOut;
		if (clearCheckOut) {
			newCheckOut = null;
		} else if (containsKey(body, "check_out")) {
			Object supplied = value(body, "check_out");
			newCheckOut = isNullOrEmptyString(supplied) ? null : LegacyValues.toPhpString(supplied);
		} else {
			Object stored = existing.get("check_out");
			newCheckOut = stored == null ? null : LegacyValues.toPhpString(stored);
		}

		// Whether THIS request supplied the exception id decides whether D-095
		// runs. An id merely inherited from the stored row does not trigger it.
		boolean exceptionSupplied = false;
		Long newException;
		if (clearException) {
			newException = null;
		} else if (containsKey(body, "exception_type_id")) {
			Object supplied = value(body, "exception_type_id");
			newException = isNullOrEmptyString(supplied) ? null : LegacyValues.toPhpLong(supplied);
			exceptionSupplied = true;
		} else {
			Object stored = existing.get("exception_type_id");
			long cast = stored == null ? 0L : LegacyValues.toPhpLong(stored);
			newException = cast == 0L ? null : cast;
		}

		// Both punches gone and no exception -> the row is DELETED, and the
		// response is still attendance_record_updated with no data.
		if ((clearCheckIn || newCheckIn == null || newCheckIn.isEmpty())
				&& (clearCheckOut || newCheckOut == null)
				&& newException == null) {
			store.deleteById(id);
			return new UpdateOutcome(true, null);
		}

		// Punches gone but an exception remains -> a midnight-anchored day,
		// dated from the row's EXISTING check_in.
		if ((clearCheckIn || newCheckIn == null || newCheckIn.isEmpty()) && newException != null) {
			Object storedCheckIn = existing.get("check_in");
			String base = storedCheckIn == null ? "now" : LegacyValues.toPhpString(storedCheckIn);
			LocalDateTime parsedBase = LegacyPhpStrtotime.dateTimeOf(base, clock.now());
			if (parsedBase == null) {
				// `date('Y-m-d', false)` is a TypeError in PHP and nothing
				// catches it here, so D-084 owns the response.
				throw new LegacyPhpDateYear.LegacyPhpDateException();
			}
			newCheckIn = parsedBase.toLocalDate() + " 00:00:00";
			newCheckOut = null;
		}

		if (newCheckIn == null || newCheckIn.isEmpty()) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "check_in"));
		}

		// Final exception-only normalization. A strtotime() that fails here is
		// merely `is_midnight = false` -- not a validation error.
		boolean hasCheckOut = newCheckOut != null && !LegacyValues.phpTrim(newCheckOut).isEmpty();
		LocalDateTime parsedIn = LegacyPhpStrtotime.dateTimeOf(newCheckIn, clock.now());
		boolean isMidnight = parsedIn != null && parsedIn.toLocalTime().equals(LocalTime.MIDNIGHT);
		if (newException != null && !hasCheckOut && isMidnight) {
			newCheckIn = parsedIn.toLocalDate() + " 00:00:00";
			newCheckOut = null;
		}

		// D-095, immediately before the write and only for an id THIS request
		// supplied. Foreign and missing are indistinguishable by design, and
		// both reach D-084's generic 500 rather than a keyed failure.
		if (exceptionSupplied && newException != null && newException > 0
				&& !store.exceptionTypeBelongsToCompany(companyId, newException)) {
			throw new ForeignExceptionTypeException();
		}

		store.update(id, newCheckIn, newCheckOut, newException);
		return new UpdateOutcome(false, requirePublicRow(store.recordFull(companyId, id)));
	}

	/**
	 * D-095's abort. Deliberately not a {@link LegacyApiException}: it must
	 * reach D-084's unexpected-exception path and render
	 * `{"success": false, "message": "Internal server error"}` with no `data`,
	 * so a caller cannot tell a foreign id from a nonexistent one, and no
	 * exception-type name, company or ownership detail is disclosed.
	 */
	static final class ForeignExceptionTypeException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		ForeignExceptionTypeException() {
			// No message: nothing about the reference may reach a log line that
			// could be surfaced.
			super("attendance update referenced an exception type outside the company");
		}

	}

	/** `delete.php`: a company-scoped existence probe, then an id-only hard delete. */
	public void delete(long companyId, long id) {
		if (!store.existsForCompany(companyId, id)) {
			throw new LegacyApiException(404, "not_found");
		}
		store.deleteById(id);
	}

	/** `delete_range.php`'s response payload and its `{count}` message replacement. */
	public record RangeOutcome(Map<String, Object> data, Map<String, Object> replace) {
	}

	/**
	 * `delete_range.php`.
	 *
	 * <h2>The query keys are `from` and `to`</h2>
	 * <p>PHP reads `$_GET[Request::DATE_FROM]`, and `Request::DATE_FROM` is
	 * declared as the literal `'from'` in `apis/config/request.php:26`
	 * -- the constant's identifier is not its value. Resolved from that file
	 * rather than guessed from the name, because guessing is exactly what
	 * produced the Wave 12.5 holiday regression. `date_from` and `date_to` are
	 * not aliases; they are unknown parameters and are ignored.
	 *
	 * <h2>Two statements, deliberately</h2>
	 * <p>PHP counts, decides, then deletes. The count is <em>not</em> derived
	 * from the delete's affected-row count, and the pair is not wrapped in a
	 * transaction, so a concurrent write between them is legacy-observable:
	 * the reported `count` can disagree with what was actually removed.
	 * Collapsing the two would hide that.
	 */
	public RangeOutcome deleteRange(long companyId, LegacyQueryParameters query) {
		// `trim((string) ($_GET[...] ?? ''))` -- PHP's charlist, so a
		// form-feed-padded value is NOT trimmed and stays non-blank.
		String fromRaw = LegacyValues.phpTrim(LegacyValues.toPhpString(query.value("from")));
		String toRaw = LegacyValues.phpTrim(LegacyValues.toPhpString(query.value("to")));
		if (fromRaw.isEmpty() || toRaw.isEmpty()) {
			// The field named is `from` when from is blank, otherwise `to` --
			// so a request with both blank reports only `from`.
			throw new LegacyApiException(400, "field_required", null,
					Map.of("field", fromRaw.isEmpty() ? "from" : "to"));
		}

		LocalDate today = clock.today();
		LocalDate from = LegacyPhpStrtotime.dateOf(fromRaw, today);
		LocalDate to = LegacyPhpStrtotime.dateOf(toRaw, today);
		if (from == null || to == null) {
			throw new LegacyApiException(400, "invalid_date");
		}

		// `$from > $to` on two `Y-m-d` strings: lexicographic in PHP, and for
		// this format that agrees with chronological order.
		if (from.isAfter(to)) {
			throw new LegacyApiException(400, "invalid_date");
		}

		String fromYmd = from.toString();
		String toYmd = to.toString();

		long deleted = store.countInRange(companyId, fromYmd, toYmd);
		if (deleted > 0) {
			store.deleteInRange(companyId, fromYmd, toYmd);
		}

		Map<String, Object> data = new LinkedHashMap<>();
		data.put("count", deleted);
		data.put("from", fromYmd);
		data.put("to", toYmd);

		// `ok($msg, $data, 200, [Response::COUNT => (string) $count])` -- the
		// fourth argument of ok() is $replace, a message placeholder map, not
		// headers (`functions.php:380`).
		return new RangeOutcome(data, Map.of("count", String.valueOf(deleted)));
	}


	/**
	 * `public_row($row)` takes an array, so a post-write re-read that comes
	 * back null is a PHP TypeError -- and nothing catches it, so D-084 owns the
	 * response.
	 *
	 * <p>Only a concurrent delete or an employee cascade can open that window,
	 * and legacy has no transaction to close it. What matters is that the race
	 * must not be quietly converted into a success with the data key omitted:
	 * the row was written, and the caller would be told nothing went wrong
	 * while receiving no record.
	 *
	 * <p>Deliberately NOT applied to update's hard-delete branch, which calls
	 * `ok(ATTENDANCE_RECORD_UPDATED, null)` on purpose -- there, an omitted
	 * data key is the contract.
	 */
	private static Map<String, Object> requirePublicRow(Map<String, Object> row) {
		if (row == null) {
			// An ordinary exception, not a LegacyApiException: it must reach
			// D-084's generic 500 rather than a keyed failure.
			throw new IllegalStateException("attendance public_row received null");
		}
		return row;
	}

	private static Object value(Map<String, Object> body, String key) {
		return body == null ? null : body.get(key);
	}

	private static boolean containsKey(Map<String, Object> body, String key) {
		return body != null && body.containsKey(key);
	}

	/** `$v === null || $v === ''` -- the exact pair `update.php` tests. */
	private static boolean isNullOrEmptyString(Object value) {
		return value == null || "".equals(value);
	}

	/** `required($body, [$field])` -- missing, null and "" fail; "0" passes. */
	private static void required(Map<String, Object> body, String... keys) {
		for (String key : keys) {
			Object supplied = value(body, key);
			if (supplied == null || "".equals(supplied)) {
				throw new LegacyApiException(400, "field_required", null, Map.of("field", key));
			}
		}
	}

}
