package com.workin.legacy.attendance.records;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyPhpStrtotime;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.wire.LegacyApiException;

/**
 * `hr-legacy/apis/api/attendance/{one,delete,delete_range}.php`
 * (Wave 12.6 slice 1a-i).
 *
 * <p>These three are the subset of the attendance module whose function-level
 * closure reaches neither `schedule_helper`, nor `company_settings`, nor
 * `payroll_calculation` (Wave 12.6 discovery §G.1), so they are implementable
 * ahead of D-091. No settings or payroll code appears here.
 *
 * <p>`create.php` and `update.php` are deliberately <b>not</b> in this slice:
 * both call `strtotime()` and then read `date('H:i:s', $ts)` to decide whether
 * a punch is real, and {@link LegacyPhpStrtotime#dateOf} projects a
 * {@link LocalDate}, discarding the time-of-day that test needs. The grammar
 * itself already resolves those inputs -- ISO datetimes, `now`, bare `HHMM` --
 * so what slice 1a-t adds is a timestamp-preserving entry point, not a second
 * parser.
 */
@Service
public class LegacyAttendanceService {

	private final LegacyAttendanceStore store;
	private final LegacyClock clock;

	public LegacyAttendanceService(LegacyAttendanceStore store, LegacyClock clock) {
		this.store = store;
		this.clock = clock;
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

}
