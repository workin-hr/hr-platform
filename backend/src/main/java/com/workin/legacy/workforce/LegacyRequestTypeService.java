package com.workin.legacy.workforce;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyPagination;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.attendance.LegacyExceptionTypeService;
import com.workin.legacy.wire.LegacyApiException;

/**
 * {@code hr-legacy/apis/api/request_types/*.php}, statement for statement.
 *
 * <h2>Three booleans, one non-obvious rule each</h2>
 * <p>{@code request_type_bool_from_body()} accepts <em>only</em> {@code true},
 * {@code 1}, {@code "1"} and {@code "true"}. Everything else is 0 --
 * {@code "yes"}, {@code "TRUE"}, {@code "on"}, {@code 2}, {@code 1.0}. And
 * {@code counts_as_paid_leave} defaults to <b>1</b> when its key is absent
 * while its two siblings default to 0, so an omitted flag means the opposite
 * thing depending on which flag it is.
 *
 * <h2>D-088</h2>
 * <p>{@code update.php}'s re-read is company-scoped here, and nothing else
 * about the endpoint moves -- see {@link #update}.
 */
@Service
public class LegacyRequestTypeService {

	/** {@code $allowed_fields}, in PHP's order -- the order decides the SET clause. */
	private static final List<String> ALLOWED_FIELDS = List.of(
			"name", "is_active", "deduct_balance", "counts_as_paid_leave",
			"add_attendance_exception", "exception_type_id");

	private final LegacyRequestTypeStore store;
	private final LegacyExceptionTypeService exceptionTypes;

	public LegacyRequestTypeService(LegacyRequestTypeStore store, LegacyExceptionTypeService exceptionTypes) {
		this.store = store;
		this.exceptionTypes = exceptionTypes;
	}

	/** A page plus its {@code pagination_meta()}. */
	public record Page(List<Map<String, Object>> rows, Map<String, Object> meta) {
	}

	/**
	 * {@code request_types/list.php}.
	 *
	 * <p>{@code isset($_GET['is_active'])} decides whether the filter is the
	 * default or the caller's, and the caller's value goes through {@code (int)}
	 * -- so {@code ?is_active=abc} filters on 0, not on 1 and not on an error.
	 * An {@code is_active=} with an empty value is {@code isset()}-true and
	 * casts to 0 as well.
	 */
	public Page list(long companyId, LegacyQueryParameters query) {
		Long isActive = query.value("is_active") == null
				? null
				: LegacyValues.toPhpLong(query.value("is_active"));
		String search = LegacyPagination.searchQueryParam(query);
		LegacyPagination.Params pagination = LegacyPagination.params(query);

		long total = store.count(companyId, isActive, search);
		return new Page(
				store.list(companyId, isActive, search, pagination),
				LegacyPagination.meta(total, pagination));
	}

	/** {@code request_types/one.php}: company-scoped, and a miss is {@code not_found} 404. */
	public Map<String, Object> one(long companyId, long id) {
		Map<String, Object> row = store.byIdForCompany(companyId, id);
		if (row == null) {
			throw new LegacyApiException(404, "not_found");
		}
		return row;
	}

	/**
	 * {@code request_types/create.php}.
	 *
	 * <p>{@code $is_active = (int) ($body['is_active'] ?? 1)} is an ordinary
	 * cast, not the boolean helper -- so {@code "2"} stores 2 in a
	 * {@code tinyint(1)}, and {@code "true"} stores 0. The three real booleans
	 * use the helper; this one does not, and the difference is legacy's.
	 */
	public Map<String, Object> create(long companyId, Map<String, Object> body) {
		required(body, "name");

		long isActive = body.get("is_active") == null ? 1L : LegacyValues.toPhpLong(body.get("is_active"));
		int deductBalance = boolFromBody(body, "deduct_balance");
		// The asymmetric default: absent means 1 here, 0 for the other two.
		int countsAsPaidLeave = body.containsKey("counts_as_paid_leave")
				? boolFromBody(body, "counts_as_paid_leave")
				: 1;
		int addAttendanceException = boolFromBody(body, "add_attendance_exception");
		Long exceptionTypeId = exceptionTypeFromBody(companyId, body);

		long id = store.insert(
				companyId, body.get("name"), isActive, deductBalance, countsAsPaidLeave,
				addAttendanceException, exceptionTypeId);
		return requirePublicRow(store.byId(id));
	}

	/**
	 * {@code request_types/update.php}, with D-088's single correction.
	 *
	 * <p>PHP's order is preserved exactly, because the order is observable:
	 * the three boolean normalisations, then the conditional
	 * {@code exception_type_id} resolution, then the whitelist, then
	 * {@code NOTHING_TO_UPDATE}, then the company-scoped UPDATE. <b>No early
	 * existence check is added</b> -- one would run before the whitelist and
	 * turn a foreign id with an empty body into a 404 where PHP answers
	 * {@code nothing_to_update}.
	 *
	 * <p>The one change is the re-read, which PHP does by id alone and which
	 * therefore returns another company's row at 200. Scoped here to id and
	 * company; an empty result is the module's own {@code not_found} 404.
	 */
	public Map<String, Object> update(long companyId, long id, Map<String, Object> rawBody) {
		Map<String, Object> body = new java.util.LinkedHashMap<>(rawBody == null ? Map.of() : rawBody);

		// `if (array_key_exists(...)) { $body[...] = request_type_bool_from_body(...) }`
		// -- normalised in place, so the whitelist below writes the 0/1 and not
		// the caller's "true".
		for (String flag : List.of("deduct_balance", "counts_as_paid_leave", "add_attendance_exception")) {
			if (body.containsKey(flag)) {
				body.put(flag, (long) boolFromBody(body, flag));
			}
		}
		// Either key triggers the resolution, and the resolution reads
		// add_attendance_exception -- so sending only exception_type_id, with
		// the flag absent, resolves to null and clears the column.
		if (body.containsKey("add_attendance_exception") || body.containsKey("exception_type_id")) {
			body.put("exception_type_id", exceptionTypeFromBody(companyId, body));
		}

		List<String> columns = new ArrayList<>();
		List<Object> values = new ArrayList<>();
		for (String field : ALLOWED_FIELDS) {
			if (body.containsKey(field)) {
				columns.add(field);
				values.add(body.get(field));
			}
		}

		if (columns.isEmpty()) {
			throw new LegacyApiException(400, "nothing_to_update");
		}

		store.update(companyId, id, columns, values);

		Map<String, Object> updated = store.byIdForCompany(companyId, id);
		if (updated == null) {
			throw new LegacyApiException(404, "not_found");
		}
		return updated;
	}

	/**
	 * {@code request_types/delete.php}: a hard delete, refused while any of
	 * <em>this company's</em> requests reference the type.
	 */
	public void delete(long companyId, long id) {
		if (store.byIdForCompany(companyId, id) == null) {
			throw new LegacyApiException(404, "not_found");
		}
		if (store.inUseCount(companyId, id) > 0) {
			throw new LegacyApiException(409, "request_type_in_use");
		}
		store.delete(companyId, id);
	}

	/**
	 * {@code request_type_bool_from_body()}
	 * ({@code helpers/request_actions_helper.php:270-278}): an exact
	 * four-value allow-list, and an absent key is 0.
	 */
	static int boolFromBody(Map<String, Object> body, String key) {
		if (body == null || !body.containsKey(key)) {
			return 0;
		}
		Object value = body.get(key);
		return Boolean.TRUE.equals(value)
				|| Long.valueOf(1L).equals(value)
				|| Integer.valueOf(1).equals(value)
				|| "1".equals(value)
				|| "true".equals(value)
				? 1
				: 0;
	}

	/**
	 * {@code request_type_exception_type_from_body()} (same file, 280-292):
	 * null unless {@code add_attendance_exception} is truthy by the rule above,
	 * then the id cast with {@code (int)} and validated for the company.
	 */
	private Long exceptionTypeFromBody(long companyId, Map<String, Object> body) {
		if (boolFromBody(body, "add_attendance_exception") != 1) {
			return null;
		}
		Object raw = body == null ? null : body.get("exception_type_id");
		long id = raw == null ? 0L : LegacyValues.toPhpLong(raw);
		if (id <= 0) {
			return null;
		}
		return exceptionTypes.validateIdForCompany(companyId, id);
	}

	/**
	 * {@code public_row($row)} takes an array, so a post-insert re-read that
	 * comes back null is a PHP TypeError -- and nothing catches it, so D-084
	 * owns the response. Only a concurrent delete can open that window; the
	 * race must not be quietly converted into a 201 with the data key omitted.
	 */
	private static Map<String, Object> requirePublicRow(Map<String, Object> row) {
		if (row == null) {
			throw new IllegalStateException("request_type public_row received null");
		}
		return row;
	}

	/** {@code required($data, [$field])} -- missing, null and "" fail; "0" passes. */
	private static void required(Map<String, Object> body, String... keys) {
		for (String key : keys) {
			Object value = body == null ? null : body.get(key);
			if (value == null || "".equals(value)) {
				throw new LegacyApiException(400, "field_required", null, Map.of("field", key));
			}
		}
	}

}
