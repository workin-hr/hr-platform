package com.workin.legacy.organization;

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
import com.workin.legacy.LegacyPagination;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Frozen PHP-compatible {@code /apis/api/branches/*.php} (Wave 12.R, D-108)
 * -- the D-074 retrofit of Wave 12.3a's original {@code /api/legacy/branches}
 * surface (now retired). Guard order on every method mirrors legacy's own
 * call sequence exactly: {@code requireAuth([COMPANY_ADMIN, HR, MANAGER])}
 * (P-7 + P-8), then {@code requireCompanyActive} (P-9) -- all six endpoints,
 * including reads (D-057: no {@code LegacyHrPermissionEnforcer} call
 * anywhere in this module, confirmed negative).
 *
 * <p>Business logic stays in {@link LegacyBranchService}, unchanged in this
 * wave except for the wire-key/exception-type fixes D-108 records. This
 * controller owns only the wire concerns D-074 is about, plus the same
 * fresh-raw-read wire-row fix D-107 established for {@code
 * attendance_exception_types}: {@code branches.created_at} is also a
 * {@code timestamp} column (session-timezone-dependent on every read), so
 * the response row is read via {@link LegacyJdbcValues#rowMapper()} against
 * {@code legacyDataSource}, never through {@link LegacyBranchView}'s
 * JPA-derived {@code Instant} fields.
 */
@RestController
@RequestMapping("/apis/api/branches")
public class LegacyBranchController {

	private final LegacyBranchService service;
	private final LegacyRequestGuard guard;
	private final LegacyMessages messages;
	private final JdbcTemplate jdbcTemplate;

	public LegacyBranchController(
			LegacyBranchService service, LegacyRequestGuard guard, LegacyMessages messages,
			DataSource legacyDataSource) {
		this.service = service;
		this.guard = guard;
		this.messages = messages;
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	@RequestMapping("/list.php")
	public LegacyApiResponse list(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = guard();
		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());
		LegacyPagination.Params page = LegacyPagination.params(query);
		String search = LegacyPagination.searchQueryParam(query);

		LegacyBranchPage result = service.list(context.companyId(), search, (int) page.page(), (int) page.limit());
		List<Map<String, Object>> rows = result.data().stream().map(this::wireListRow).toList();
		Map<String, Object> meta = LegacyPagination.meta(result.total(), page);
		return LegacyApiResponse.ok(message(request, "branches"), rows, meta);
	}

	@RequestMapping("/one.php")
	public LegacyApiResponse one(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = guard();
		long id = requiredId(request);
		service.one(context.companyId(), id);
		return LegacyApiResponse.ok(message(request, "branches"), wireRow(id));
	}

	@RequestMapping("/create.php")
	public ResponseEntity<LegacyApiResponse> create(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = guard();
		Map<String, Object> body = LegacyJsonBody.read(request);
		LegacyBranchView created = service.create(context.companyId(), body);
		return ResponseEntity.status(201)
				.body(LegacyApiResponse.ok(message(request, "branch_created"), wireRow(created.id())));
	}

	@RequestMapping("/update.php")
	public LegacyApiResponse update(HttpServletRequest request) {
		requireMethod(request, "PUT");
		LegacyRequestContext context = guard();
		long id = requiredId(request);
		Map<String, Object> body = LegacyJsonBody.read(request);
		service.update(context.companyId(), id, body);
		return LegacyApiResponse.ok(message(request, "branch_updated"), wireRow(id));
	}

	@RequestMapping("/delete.php")
	public LegacyApiResponse delete(HttpServletRequest request) {
		requireMethod(request, "DELETE");
		LegacyRequestContext context = guard();
		service.delete(context.companyId(), requiredId(request));
		return LegacyApiResponse.ok(message(request, "branch_deleted"), null);
	}

	@RequestMapping("/generate_qr.php")
	public LegacyApiResponse generateQr(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = guard();
		long id = requiredId(request);
		Map<String, Object> body = LegacyJsonBody.read(request);
		service.generateQr(context.companyId(), id, body);
		return LegacyApiResponse.ok(message(request, "qr_generated"), wireRow(id));
	}

	/** Every endpoint in this module shares this exact gate sequence (D-057). */
	private LegacyRequestContext guard() {
		LegacyRequestContext context = guard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR, LegacyEmployee.Role.MANAGER);
		guard.requireCompanyActive(context.companyId());
		return context;
	}

	/** {@code public_row($branch)}: every column, read fresh (see the class javadoc). */
	private Map<String, Object> wireRow(long id) {
		return jdbcTemplate.queryForObject("SELECT * FROM branches WHERE id=?", LegacyJdbcValues.rowMapper(), id);
	}

	/** {@code list.php}'s per-row shape: the raw row plus the correlated {@code employees_count} subquery. */
	private Map<String, Object> wireListRow(LegacyBranchListItem item) {
		Map<String, Object> row = new LinkedHashMap<>(wireRow(item.id()));
		row.put("employees_count", item.employeesCount());
		return row;
	}

	/**
	 * {@code (int) ($_GET[Request::ID] ?? 0); if (!$id) fail(LangKey::ID_REQUIRED);} -- not
	 * {@code required()}'s isset/empty-string check: a non-numeric or zero id fails the same
	 * way, but a negative id is truthy in PHP and passes through to a 404 lookup miss.
	 */
	private static long requiredId(HttpServletRequest request) {
		Object raw = LegacyQueryParameters.parse(request.getQueryString()).value("id");
		long id = raw == null ? 0 : LegacyValues.toPhpLong(raw);
		if (id == 0) {
			throw new LegacyApiException(400, "id_required");
		}
		return id;
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
