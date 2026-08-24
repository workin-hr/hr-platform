package com.workin.legacy.workforce;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyPagination;
import com.workin.legacy.LegacyPhpArray;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.wire.LegacyApiException;

/**
 * {@code hr-legacy/apis/api/company_official_holidays/*.php}.
 *
 * <p>Three things here are unusual enough to state up front:
 *
 * <ul>
 * <li>{@code create.php} is an <b>upsert over a list</b>, not a create. It
 *     returns an array at 201 even for one date, and even when every date
 *     merely renamed an existing row.</li>
 * <li>It is deliberately <b>not transactional</b>. Each date is its own probe
 *     plus write, so a failure on the fourth leaves the first three committed.</li>
 * <li>{@code update.php} has <b>no nothing-to-update branch</b>. An empty body
 *     is a successful no-op that rewrites the stored name and date onto
 *     themselves.</li>
 * </ul>
 */
@Service
public class LegacyOfficialHolidayService {

	private final LegacyOfficialHolidayStore store;

	public LegacyOfficialHolidayService(LegacyOfficialHolidayStore store) {
		this.store = store;
	}

	/** A page plus its {@code pagination_meta()}. */
	public record Page(List<Map<String, Object>> rows, Map<String, Object> meta) {
	}

	/**
	 * {@code list.php}.
	 *
	 * <p><b>The query keys are {@code from} and {@code to}</b>, not
	 * {@code date_from}/{@code date_to}. PHP reads
	 * {@code $_GET[Request::DATE_FROM]}, and {@code Request::DATE_FROM} is
	 * declared as the literal {@code 'from'} in {@code apis/config/request.php:26}
	 * -- the constant's identifier is not its value. {@code employees/list.php}
	 * and {@code employees/stats.php} use the same two constants.
	 *
	 * <p>The bounds are {@code !empty()}-guarded, not {@code isset()}-guarded,
	 * so {@code ?from=0} and {@code ?from=} add no clause at all -- unlike
	 * {@code request_types}' {@code is_active}, where presence alone decides.
	 * The value is otherwise passed to the comparison unvalidated: PHP does not
	 * check it parses as a date, and neither does this.
	 */
	public Page list(long companyId, LegacyQueryParameters query) {
		String dateFrom = nonEmptyQueryValue(query, "from");
		String dateTo = nonEmptyQueryValue(query, "to");
		String search = LegacyPagination.searchQueryParam(query);
		LegacyPagination.Params pagination = LegacyPagination.params(query);

		long total = store.count(companyId, dateFrom, dateTo, search);
		return new Page(
				store.list(companyId, dateFrom, dateTo, search, pagination),
				LegacyPagination.meta(total, pagination));
	}

	/** {@code one.php}: the helper's own lookup, and a miss is {@code not_found} 404. */
	public Map<String, Object> one(long companyId, long id) {
		Map<String, Object> row = store.assertCompanyRow(companyId, id);
		if (row == null) {
			throw new LegacyApiException(404, "not_found");
		}
		return row;
	}

	/**
	 * {@code create.php}.
	 *
	 * <p>The date source is chosen by a three-branch {@code if/elseif/elseif},
	 * and the branch is decided <b>before</b> normalization. That ordering is
	 * observable: once a non-empty {@code holiday_dates} array wins, a list of
	 * entirely invalid dates does <em>not</em> fall through to
	 * {@code holiday_date} or {@code dates}. It normalizes to nothing and the
	 * request fails with {@code field_required} naming {@code holiday_dates}.
	 */
	public List<Map<String, Object>> create(long companyId, Map<String, Object> body) {
		required(body, "name");

		String name = LegacyValues.phpTrim(LegacyValues.toPhpString(value(body, "name")));
		if (name.isEmpty()) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "name"));
		}

		List<String> dates = resolveDates(body);
		if (dates.isEmpty()) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "holiday_dates"));
		}

		List<Map<String, Object>> written = new ArrayList<>();
		for (String date : dates) {
			// One probe and one write per date, with no surrounding
			// transaction. A failure part-way leaves everything before it
			// committed, and the unique constraint is not caught -- a lost race
			// escapes as D-084's 500 after earlier dates have already landed.
			Long existingId = store.existingIdForDate(companyId, date);
			if (existingId != null) {
				store.updateNameById(existingId, name);
				Map<String, Object> row = store.assertCompanyRow(companyId, existingId);
				if (row != null) {
					written.add(row);
				}
				continue;
			}

			long id = store.insert(companyId, name, date);
			Map<String, Object> row = store.assertCompanyRow(companyId, id);
			if (row != null) {
				written.add(row);
			}
		}

		return written;
	}

	/**
	 * {@code update.php}.
	 *
	 * <p>Note what is <em>not</em> here: no whitelist and no
	 * {@code nothing_to_update}. An empty body -- or a malformed one, which
	 * {@code body()} decodes to {@code []} -- keeps the stored name and the
	 * stored date and writes them back, answering 200. That is a successful
	 * no-op, not an error.
	 *
	 * <p>The date is {@code !empty()}-guarded, so an explicit {@code null},
	 * {@code ""} or {@code 0} in {@code holiday_date} preserves the stored date
	 * rather than clearing it or failing. Only a <em>non-empty</em> value that
	 * fails normalization is {@code invalid_input}.
	 */
	public Map<String, Object> update(long companyId, long id, Map<String, Object> body) {
		Map<String, Object> row = store.assertCompanyRow(companyId, id);
		if (row == null) {
			throw new LegacyApiException(404, "not_found");
		}

		// `array_key_exists(NAME, $body) ? trim((string) $body[NAME]) : (string) ($row[NAME] ?? '')`
		String name = body != null && body.containsKey("name")
				? LegacyValues.phpTrim(LegacyValues.toPhpString(body.get("name")))
				: LegacyValues.toPhpString(row.get("name"));
		if (name.isEmpty()) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "name"));
		}

		String date = LegacyValues.toPhpString(row.get("holiday_date"));
		Object suppliedDate = value(body, "holiday_date");
		if (!LegacyValues.isPhpEmpty(suppliedDate)) {
			List<String> normalized = LegacyOfficialHolidayDates.normalize(
					List.of(LegacyValues.toPhpString(suppliedDate)));
			if (normalized.isEmpty()) {
				throw new LegacyApiException(
						400, "invalid_input", null, Map.of("field", "holiday_date"));
			}
			date = normalized.get(0);
		}

		if (store.conflictExists(companyId, date, id)) {
			throw new LegacyApiException(409, "invalid_input", null, Map.of("field", "holiday_date"));
		}

		store.update(companyId, id, name, date);

		// `public_row($updated ?? $row)`: if the post-update re-read comes back
		// empty -- which only a concurrent delete can cause -- PHP renders the
		// row it read *before* the update rather than failing. Reproduced, so a
		// race answers 200 with stale data instead of a 404 legacy never sends.
		Map<String, Object> updated = store.assertCompanyRow(companyId, id);
		return updated == null ? row : updated;
	}

	/** {@code delete.php}: scoped existence check, then a hard company-scoped delete. */
	public void delete(long companyId, long id) {
		if (store.assertCompanyRow(companyId, id) == null) {
			throw new LegacyApiException(404, "not_found");
		}
		store.delete(companyId, id);
	}

	/**
	 * The {@code if / elseif / elseif} chain, in PHP's order. Each branch tests
	 * {@code !empty()} first, and the two list branches additionally test
	 * {@code is_array()} -- so a non-empty <em>scalar</em> in
	 * {@code holiday_dates} fails the array test and falls through to
	 * {@code holiday_date}, while a non-empty <em>array</em> claims the branch
	 * outright.
	 */
	private static List<String> resolveDates(Map<String, Object> body) {
		Object holidayDates = value(body, "holiday_dates");
		if (!LegacyValues.isPhpEmpty(holidayDates) && LegacyPhpArray.isArray(holidayDates)) {
			return LegacyOfficialHolidayDates.normalize(holidayDates);
		}

		Object holidayDate = value(body, "holiday_date");
		if (!LegacyValues.isPhpEmpty(holidayDate)) {
			// `[(string) $body[HOLIDAY_DATE]]` -- a single-element list, so an
			// array here becomes the string "Array" and normalizes to nothing.
			return LegacyOfficialHolidayDates.normalize(
					List.of(LegacyValues.toPhpString(holidayDate)));
		}

		Object dates = value(body, "dates");
		if (!LegacyValues.isPhpEmpty(dates) && LegacyPhpArray.isArray(dates)) {
			return LegacyOfficialHolidayDates.normalize(dates);
		}

		return List.of();
	}

	/** {@code !empty($_GET[$key])} on a query parameter. */
	private static String nonEmptyQueryValue(LegacyQueryParameters query, String key) {
		Object raw = query.value(key);
		return LegacyValues.isPhpEmpty(raw) ? null : LegacyValues.toPhpString(raw);
	}

	private static Object value(Map<String, Object> body, String key) {
		return body == null ? null : body.get(key);
	}

	/** {@code required($body, [$field])} -- missing, null and "" fail; "0" passes. */
	private static void required(Map<String, Object> body, String... keys) {
		for (String key : keys) {
			Object value = value(body, key);
			if (value == null || "".equals(value)) {
				throw new LegacyApiException(400, "field_required", null, Map.of("field", key));
			}
		}
	}

}
