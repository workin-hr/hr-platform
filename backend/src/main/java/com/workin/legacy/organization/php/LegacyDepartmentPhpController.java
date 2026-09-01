package com.workin.legacy.organization.php;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.legacy.LegacyJdbcValues;
import com.workin.legacy.LegacyJsonBody;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.organization.LegacyDepartmentService;
import com.workin.legacy.organization.LegacyDepartmentView;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Literal PHP wire adapter for {@code /apis/api/departments/*.php}.
 *
 * <p>The already-audited Wave 12.3b service remains authoritative for business
 * behavior. This class owns only D-074 wire concerns: PHP paths, method order,
 * query/body coercion, envelope, snake_case keys, and lexical TIMESTAMP reads.
 */
@RestController
@RequestMapping("/apis/api/departments")
public class LegacyDepartmentPhpController {

	private final LegacyDepartmentService service;
	private final LegacyRequestGuard guard;
	private final LegacyMessages messages;
	private final JdbcTemplate jdbcTemplate;

	public LegacyDepartmentPhpController(
			LegacyDepartmentService service,
			LegacyRequestGuard guard,
			LegacyMessages messages,
			DataSource legacyDataSource) {
		this.service = service;
		this.guard = guard;
		this.messages = messages;
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/**
	 * The list is intentionally unpaginated for PHP compatibility, so lexical wire-row reads are
	 * batched by company rather than re-fetching each department by id. The service still owns
	 * filtering and joins; this adapter performs one additional company-scoped raw-row query to
	 * preserve MariaDB's timestamp/number wire representation without an N+1 round-trip pattern.
	 */
	@RequestMapping("/list.php")
	public LegacyApiResponse list(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = guard();
		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());
		Object rawBranchId = query.value("branch_id");
		String rawBranchIds = LegacyValues.phpTrim(LegacyValues.toPhpString(query.value("branch_ids")));
		List<String> branchIds = rawBranchIds.isEmpty() ? List.of() : Arrays.asList(rawBranchIds.split(","));
		List<LegacyDepartmentView> views = service.list(
				context.companyId(), LegacyValues.toPhpLong(rawBranchId), branchIds);
		Map<Long, Map<String, Object>> wireRowsById = wireRowsByCompany(context.companyId());
		List<Map<String, Object>> rows = views.stream().map(view -> wireRow(view, wireRowsById)).toList();
		return LegacyApiResponse.ok(message(request, "departments"), rows);
	}

	@RequestMapping("/one.php")
	public LegacyApiResponse one(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = guard();
		LegacyDepartmentView view = service.one(context.companyId(), requiredId(request));
		return LegacyApiResponse.ok(message(request, "departments"), wireRow(view, true));
	}

	@RequestMapping("/create.php")
	public ResponseEntity<LegacyApiResponse> create(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = guard();
		Map<String, Object> body = LegacyJsonBody.read(request);
		required(body, "name");
		required(body, "branch_ids");
		LegacyDepartmentView created = service.create(context.companyId(), body);
		return ResponseEntity.status(201)
				.body(LegacyApiResponse.ok(message(request, "department_created"), wireRow(created, false)));
	}

	@RequestMapping("/update.php")
	public LegacyApiResponse update(HttpServletRequest request) {
		requireMethod(request, "PUT");
		LegacyRequestContext context = guard();
		long id = requiredId(request);
		Map<String, Object> body = LegacyJsonBody.read(request);
		if (body.isEmpty()) {
			throw new LegacyApiException(400, "nothing_to_update");
		}
		LegacyDepartmentView updated = service.update(context.companyId(), id, body)
				.orElseThrow(() -> new IllegalStateException("legacy department update response has no joined branch"));
		return LegacyApiResponse.ok(message(request, "department_updated"), wireRow(updated, false));
	}

	@RequestMapping("/delete.php")
	public LegacyApiResponse delete(HttpServletRequest request) {
		requireMethod(request, "DELETE");
		LegacyRequestContext context = guard();
		service.delete(context.companyId(), requiredId(request));
		return LegacyApiResponse.ok(message(request, "department_deactivated"), null);
	}

	private LegacyRequestContext guard() {
		LegacyRequestContext context = guard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR, LegacyEmployee.Role.MANAGER);
		guard.requireCompanyActive(context.companyId());
		return context;
	}

	private Map<String, Object> wireRow(LegacyDepartmentView view, boolean includeCompanyId) {
		String select = includeCompanyId
				? "SELECT id, company_id, manager_id, name, is_active, created_at FROM departments WHERE id=?"
				: "SELECT id, manager_id, name, is_active, created_at FROM departments WHERE id=?";
		Map<String, Object> row = new LinkedHashMap<>(
				jdbcTemplate.queryForObject(select, LegacyJdbcValues.rowMapper(), view.id()));
		return enrichWireRow(row, view);
	}

	private Map<String, Object> wireRow(
			LegacyDepartmentView view, Map<Long, Map<String, Object>> wireRowsById) {
		Map<String, Object> raw = wireRowsById.get(view.id());
		if (raw == null) {
			throw new IllegalStateException("legacy department wire row missing for id " + view.id());
		}
		return enrichWireRow(new LinkedHashMap<>(raw), view);
	}

	private static Map<String, Object> enrichWireRow(Map<String, Object> row, LegacyDepartmentView view) {
		row.put("branch_ids", view.branchIds());
		row.put("branch_names", view.branchNames());
		row.put("manager_name", view.managerName());
		return row;
	}

	private Map<Long, Map<String, Object>> wireRowsByCompany(long companyId) {
		Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
		for (Map<String, Object> row : jdbcTemplate.query(
				"SELECT id, company_id, manager_id, name, is_active, created_at FROM departments WHERE company_id=?",
				LegacyJdbcValues.rowMapper(), companyId)) {
			byId.put(LegacyValues.toPhpLong(row.get("id")), row);
		}
		return byId;
	}

	private static long requiredId(HttpServletRequest request) {
		Object raw = LegacyQueryParameters.parse(request.getQueryString()).value("id");
		long id = raw == null ? 0 : LegacyValues.toPhpLong(raw);
		if (id == 0) {
			throw new LegacyApiException(400, "id_required");
		}
		return id;
	}

	private static void required(Map<String, Object> body, String field) {
		if (!body.containsKey(field) || body.get(field) == null || "".equals(body.get(field))) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", field));
		}
	}

	private static void requireMethod(HttpServletRequest request, String expected) {
		if (!expected.equals(request.getMethod())) {
			throw new LegacyApiException(405, "invalid_method");
		}
	}

	private String message(HttpServletRequest request, String key) {
		return messages.translate(messages.resolveLocale(request), key, null);
	}
}
