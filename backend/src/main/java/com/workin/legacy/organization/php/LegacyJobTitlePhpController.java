package com.workin.legacy.organization.php;

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
import com.workin.legacy.organization.LegacyJobTitleService;
import com.workin.legacy.organization.LegacyJobTitleView;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.http.HttpServletRequest;

/** Literal D-074 adapter for {@code /apis/api/job_titles/*.php}. */
@RestController
@RequestMapping("/apis/api/job_titles")
public class LegacyJobTitlePhpController {

	private final LegacyJobTitleService service;
	private final LegacyRequestGuard guard;
	private final LegacyMessages messages;
	private final JdbcTemplate jdbcTemplate;

	public LegacyJobTitlePhpController(
			LegacyJobTitleService service,
			LegacyRequestGuard guard,
			LegacyMessages messages,
			DataSource legacyDataSource) {
		this.service = service;
		this.guard = guard;
		this.messages = messages;
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/**
	 * PR #120 review (P2): {@code wireRow(LegacyJobTitleView)} re-fetches its row with one
	 * {@code SELECT * FROM job_titles WHERE id=?} per call -- necessary for wire-faithful
	 * {@code created_at} formatting (the same TIMESTAMP-vs-DATETIME fresh-read pattern {@link
	 * LegacyJdbcValues#rowMapper()} exists for elsewhere), but calling it once per row here
	 * turned an unpaginated list into one extra round trip per job title. {@link
	 * #wireRowsByCompany} fetches every one of the company's job-title wire rows in a single
	 * query instead; {@code one.php}/{@code create.php}/{@code update.php} still fetch a
	 * single row each via {@link #wireRow(LegacyJobTitleView)}, unaffected.
	 */
	@RequestMapping("/list.php")
	public LegacyApiResponse list(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = guard();
		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());
		List<LegacyJobTitleView> views = service.list(
				context.companyId(),
				LegacyValues.toPhpLong(query.value("branch_id")),
				LegacyValues.toPhpLong(query.value("department_id")));
		Map<Long, Map<String, Object>> wireRowsById = wireRowsByCompany(context.companyId());
		List<Map<String, Object>> rows = views.stream().map(view -> wireRow(view, wireRowsById)).toList();
		return LegacyApiResponse.ok(message(request, "job_titles"), rows);
	}

	@RequestMapping("/one.php")
	public LegacyApiResponse one(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = guard();
		LegacyJobTitleView view = service.one(context.companyId(), requiredId(request));
		return LegacyApiResponse.ok(message(request, "job_titles"), wireRow(view));
	}

	@RequestMapping("/create.php")
	public ResponseEntity<LegacyApiResponse> create(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = guard();
		Map<String, Object> body = LegacyJsonBody.read(request);
		required(body, "name");
		LegacyJobTitleView created = service.create(context.companyId(), body);
		return ResponseEntity.status(201)
				.body(LegacyApiResponse.ok(message(request, "job_title_created"), wireRow(created)));
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
		LegacyJobTitleView updated = service.update(context.companyId(), id, body);
		return LegacyApiResponse.ok(message(request, "job_title_updated"), wireRow(updated));
	}

	@RequestMapping("/delete.php")
	public LegacyApiResponse delete(HttpServletRequest request) {
		requireMethod(request, "DELETE");
		LegacyRequestContext context = guard();
		service.delete(context.companyId(), requiredId(request));
		return LegacyApiResponse.ok(message(request, "job_title_deleted"), null);
	}

	private LegacyRequestContext guard() {
		LegacyRequestContext context = guard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR, LegacyEmployee.Role.MANAGER);
		guard.requireCompanyActive(context.companyId());
		return context;
	}

	private Map<String, Object> wireRow(LegacyJobTitleView view) {
		Map<String, Object> row = new LinkedHashMap<>(
				jdbcTemplate.queryForObject("SELECT * FROM job_titles WHERE id=?", LegacyJdbcValues.rowMapper(), view.id()));
		row.put("department_name", view.departmentName());
		row.put("branches_summary", view.branchesSummary());
		return row;
	}

	private Map<String, Object> wireRow(LegacyJobTitleView view, Map<Long, Map<String, Object>> wireRowsById) {
		Map<String, Object> row = new LinkedHashMap<>(wireRowsById.get(view.id()));
		row.put("department_name", view.departmentName());
		row.put("branches_summary", view.branchesSummary());
		return row;
	}

	private Map<Long, Map<String, Object>> wireRowsByCompany(long companyId) {
		Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
		for (Map<String, Object> row
				: jdbcTemplate.query("SELECT * FROM job_titles WHERE company_id=?", LegacyJdbcValues.rowMapper(), companyId)) {
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
