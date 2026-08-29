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
 * {@code assets/*.php} -- the five custody endpoints.
 *
 * <h2>No {@code hr_permissions} gate anywhere in this module</h2>
 * <p>Deliberate on legacy's part or not, it is the module's recorded
 * inconsistency (completion plan §2.2), and the bounded C3/C8 pass found a
 * client that relies on it: the desktop sidebar hides Assets behind
 * {@code HrPermissionFlag.assets} while the server enforces nothing, so any
 * authenticated user in the company can call {@code create}, {@code update} and
 * {@code delete} directly. The port reproduces that under D-058 and D-130
 * records it as an accepted risk rather than leaving it to be rediscovered.
 *
 * <h2>{@code list} admits EMPLOYEE; {@code one} does not</h2>
 * <p>An employee may page their own custody records but cannot fetch one by id
 * -- {@code one.php} authenticates only ADMIN, HR and MANAGER. So a client that
 * renders a list and then a detail view works for an employee on the first
 * screen and 403s on the second.
 */
@Service
public class LegacyAssetService {

	private final LegacyAssetStore store;

	public LegacyAssetService(LegacyAssetStore store) {
		this.store = store;
	}

	public record Page(List<Map<String, Object>> rows, Map<String, Object> meta) {
	}

	public Page list(LegacyRequestContext context, LegacyQueryParameters query) {
		List<String> where = new ArrayList<>(List.of("c.company_id=?"));
		List<Object> binds = new ArrayList<>(List.of(context.companyId()));

		if (context.role() == LegacyEmployee.Role.EMPLOYEE) {
			long employeeId = context.employeeId();
			if (employeeId <= 0) {
				throw new LegacyApiException(403, "forbidden");
			}
			where.add("c.employee_id=?");
			binds.add(employeeId);
		} else if (!LegacyValues.isPhpEmpty(query.value("employee_id"))) {
			// `!empty(...)`, so ?employee_id=0 is ignored rather than matching
			// employee 0 -- an employee filter of "0" silently lists everyone.
			where.add("c.employee_id=?");
			binds.add(LegacyValues.toPhpLong(query.value("employee_id")));
		}

		// `isset(...)` rather than `!empty(...)`: ?is_returned=0 DOES filter,
		// unlike the employee filter two lines above. The two guards differ
		// within the same handler.
		if (query.value("is_returned") != null) {
			where.add("c.is_returned=?");
			binds.add(LegacyValues.toPhpLong(query.value("is_returned")));
		}

		String search = LegacyPagination.searchQueryParam(query);
		if (search != null) {
			where.add("(TRIM(CONCAT(COALESCE(e.first_name,''),' ',COALESCE(e.last_name,'')))"
					+ " LIKE ? OR e.employee_code LIKE ? OR c.asset_text LIKE ?)");
			String like = "%" + search + "%";
			binds.add(like);
			binds.add(like);
			binds.add(like);
		}

		if (!LegacyValues.isPhpEmpty(query.value("date_from"))) {
			where.add("c.asset_date >= ?");
			binds.add(LegacyValues.toPhpString(query.value("date_from")));
		}
		if (!LegacyValues.isPhpEmpty(query.value("date_to"))) {
			where.add("c.asset_date <= ?");
			binds.add(LegacyValues.toPhpString(query.value("date_to")));
		}

		LegacyPagination.Params pagination = LegacyPagination.params(query);
		long total = store.count(where, binds);
		return new Page(
				store.page(where, binds, pagination.limit(), pagination.offset()),
				LegacyPagination.meta(total, pagination));
	}

	public Map<String, Object> one(long companyId, long id) {
		Map<String, Object> row = store.one(companyId, id);
		if (row == null) {
			throw new LegacyApiException(404, "not_found");
		}
		return row;
	}

	/**
	 * {@code create.php}.
	 *
	 * <p>The employee is looked up by id alone and its company compared in PHP,
	 * so a foreign employee id answers {@code employee_not_found} (404) rather
	 * than a 403 -- the same message a genuinely missing id produces, which is
	 * what keeps it from confirming that the id exists in another tenant.
	 */
	public Map<String, Object> create(long companyId, Map<String, Object> body) {
		requirePresent(body, "employee_id");
		requirePresent(body, "asset_date");
		requirePresent(body, "asset_text");

		long employeeId = LegacyValues.toPhpLong(body.get("employee_id"));
		String assetDate = LegacyValues.toPhpString(body.get("asset_date"));
		String assetText = LegacyValues.phpTrim(LegacyValues.toPhpString(body.get("asset_text")));
		if (assetText.isEmpty()) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "asset_text"));
		}

		Long employeeCompany = store.employeeCompanyId(employeeId);
		if (employeeCompany == null || employeeCompany != companyId) {
			throw new LegacyApiException(404, "employee_not_found");
		}

		String returnedAt = LegacyValues.isPhpEmpty(body.get("returned_at"))
				? null : LegacyValues.toPhpString(body.get("returned_at"));

		// `array_key_exists` first, and only then the returned_at inference --
		// so an explicit `is_returned: false` alongside a returned_at wins and
		// stores 0, while omitting the key entirely infers 1 from the date.
		int isReturned = 0;
		if (body.containsKey("is_returned")) {
			isReturned = LegacyValues.toPhpFilterBoolean(body.get("is_returned")) ? 1 : 0;
		} else if (returnedAt != null) {
			isReturned = 1;
		}

		long id = store.insert(companyId, employeeId, assetDate, assetText, returnedAt, isReturned);
		return store.byId(id);
	}

	/** {@code update.php}: a whitelist of four columns, and nothing else is writable. */
	public Map<String, Object> update(long companyId, long id, Map<String, Object> body) {
		if (store.row(companyId, id) == null) {
			throw new LegacyApiException(404, "not_found");
		}

		List<String> assignments = new ArrayList<>();
		List<Object> values = new ArrayList<>();
		for (String field : List.of("asset_date", "asset_text", "returned_at", "is_returned")) {
			if (body.containsKey(field)) {
				assignments.add("`" + field + "`=?");
				// PDO binds a scalar unchanged and converts an array/object to the
				// literal "Array"; only that second case needs coercing here.
				values.add("is_returned".equals(field)
						? (LegacyValues.toPhpFilterBoolean(body.get(field)) ? 1 : 0)
						: LegacyValues.toPdoBindValue(body.get(field)));
			}
		}
		if (assignments.isEmpty()) {
			throw new LegacyApiException(400, "nothing_to_update");
		}
		store.update(id, assignments, values);
		return store.afterUpdate(id);
	}

	public void delete(long companyId, long id) {
		if (store.row(companyId, id) == null) {
			throw new LegacyApiException(404, "not_found");
		}
		store.delete(companyId, id);
	}

	/** {@code required($body, [...])}: present and non-empty-string. */
	private static void requirePresent(Map<String, Object> body, String field) {
		Object value = body.get(field);
		if (value == null || "".equals(value)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", field));
		}
	}
}
