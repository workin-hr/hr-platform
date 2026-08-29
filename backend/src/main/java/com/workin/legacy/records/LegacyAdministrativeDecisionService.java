package com.workin.legacy.records;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyPagination;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.wire.LegacyApiException;

/**
 * {@code administrative_decisions/*.php}.
 *
 * <h2>{@code list} has its own authorization, written by hand</h2>
 * <p>It calls a bare {@code requireAuth()} and then decides for itself: an
 * EMPLOYEE passes with <b>no permission check</b> but sees only
 * {@code is_active = 1} rows; COMPANY_ADMIN and MANAGER pass unconditionally;
 * HR additionally needs {@code can_employees}. Every other endpoint in the
 * module takes ADMIN or HR and always requires {@code can_employees}.
 *
 * <p>So <b>MANAGER can list decisions but cannot read one by id</b>, and an HR
 * user without {@code can_employees} is refused the list while an ordinary
 * employee is served it. The permission guards the management view, not the
 * data -- which is a defensible design and an easy one to "tidy" into
 * something stricter, changing what two roles can see.
 */
@Service
public class LegacyAdministrativeDecisionService {

	private final LegacyAdministrativeDecisionStore store;

	public LegacyAdministrativeDecisionService(LegacyAdministrativeDecisionStore store) {
		this.store = store;
	}

	public record Page(List<Map<String, Object>> rows, Map<String, Object> meta) {
	}

	public Page list(LegacyRequestContext context, LegacyQueryParameters query) {
		List<String> where = new ArrayList<>(List.of("company_id=?"));
		List<Object> binds = new ArrayList<>(List.of(context.companyId()));

		if (context.role() == LegacyEmployee.Role.EMPLOYEE) {
			where.add("is_active=1");
		}

		String search = LegacyPagination.searchQueryParam(query);
		if (search != null) {
			where.add("(title LIKE ? OR body LIKE ?)");
			String like = "%" + search + "%";
			binds.add(like);
			binds.add(like);
		}

		LegacyPagination.Params pagination = LegacyPagination.params(query);
		long total = store.count(where, binds);
		return new Page(
				store.page(where, binds, pagination.limit(), pagination.offset()),
				LegacyPagination.meta(total, pagination));
	}

	public Map<String, Object> one(long companyId, long id) {
		Map<String, Object> row = store.assertCompanyRow(companyId, id);
		if (row == null) {
			throw new LegacyApiException(404, "not_found");
		}
		return row;
	}

	public Map<String, Object> create(long companyId, Map<String, Object> body) {
		String title = requiredText(body, "title");
		String text = requiredText(body, "body");
		long id = store.insert(companyId, title, text);
		// `public_row($row ?? [])`: a re-read that comes back empty renders an
		// empty object rather than failing, so a 201 with `data: {}` is a state
		// legacy can actually produce.
		Map<String, Object> row = store.assertCompanyRow(companyId, id);
		return row == null ? Map.of() : row;
	}

	/**
	 * {@code update.php}: a full replace of the three columns, each defaulting
	 * to the row's current value when the key is absent.
	 *
	 * <p>{@code is_active} is {@code (int) $body[...] === 1 ? 1 : 0}, an exact
	 * comparison rather than a truthiness test -- so {@code "true"} casts to 0
	 * and <b>deactivates</b> the decision, where {@code assets} in the same
	 * wave uses {@code filter_var(FILTER_VALIDATE_BOOLEAN)} and would activate
	 * it. Two boolean conventions, two modules, same request body.
	 */
	public Map<String, Object> update(long companyId, long id, Map<String, Object> body) {
		Map<String, Object> row = store.assertCompanyRow(companyId, id);
		if (row == null) {
			throw new LegacyApiException(404, "not_found");
		}

		String title = body.containsKey("title")
				? LegacyValues.phpTrim(LegacyValues.toPhpString(body.get("title")))
				: LegacyValues.toPhpString(row.get("title"));
		String text = body.containsKey("body")
				? LegacyValues.phpTrim(LegacyValues.toPhpString(body.get("body")))
				: LegacyValues.toPhpString(row.get("body"));
		if (title.isEmpty()) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "title"));
		}
		if (text.isEmpty()) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "body"));
		}

		int isActive = body.containsKey("is_active")
				? (LegacyValues.toPhpLong(body.get("is_active")) == 1L ? 1 : 0)
				: (int) LegacyValues.toPhpLong(row.get("is_active"));

		store.update(companyId, id, title, text, isActive);
		Map<String, Object> updated = store.assertCompanyRow(companyId, id);
		return updated == null ? row : updated;
	}

	/** {@code delete.php}: {@code ok(OK, null)} -- no {@code data} key at all. */
	public void delete(long companyId, long id) {
		if (store.assertCompanyRow(companyId, id) == null) {
			throw new LegacyApiException(404, "not_found");
		}
		store.delete(companyId, id);
	}

	private static String requiredText(Map<String, Object> body, String field) {
		Object raw = body.get(field);
		if (raw == null || "".equals(raw)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", field));
		}
		String text = LegacyValues.phpTrim(LegacyValues.toPhpString(raw));
		if (text.isEmpty()) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", field));
		}
		return text;
	}
}
