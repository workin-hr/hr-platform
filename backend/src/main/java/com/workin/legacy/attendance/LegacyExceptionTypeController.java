package com.workin.legacy.attendance;

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
import com.workin.legacy.authorization.LegacyHrPermissionEnforcer;
import com.workin.legacy.authorization.LegacyHrPermissionKey;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Frozen PHP-compatible {@code /apis/api/attendance_exception_types/*.php}
 * (Wave 12.R, D-107) -- the D-074 retrofit of Wave 12.1's original
 * {@code /api/legacy/attendance_exception_types} surface (now retired; see
 * {@link com.workin.legacy.wire.LegacyWireExceptionHandler}'s updated package
 * list). Guard order on every method mirrors legacy's own call sequence
 * exactly: {@code requireAuth} (P-7 + P-8), {@code requireCompanyActive}
 * (P-9), then -- writes only -- {@code can_company_settings} (P-3, D-045).
 * List/one carry no permission gate on purpose; adding one would be
 * authorization legacy does not have.
 *
 * <p>Business logic (uniqueness, FK-clearing delete, pagination bounds)
 * stays in {@link LegacyExceptionTypeService}, unchanged in this wave except
 * for the {@code is_active} coercion fix D-107 records. This controller owns
 * only the wire concerns D-074 is about: the literal route, the
 * {@code {success,message,data,meta}} envelope, and snake_case field names.
 *
 * <h2>Why the response row is a fresh raw read, not the JPA view</h2>
 * <p>{@link LegacyExceptionTypeView#createdAt()}/{@code updatedAt()} are
 * {@code Instant}, produced by Hibernate's own {@code DATETIME}-to-{@code
 * Instant} conversion -- exactly the class of read {@link
 * com.workin.legacy.LegacyJdbcValues}'s own javadoc warns against ("Legacy
 * exposes the stored text, so temporal columns are read as strings and never
 * through JDBC temporal objects"). Measured here: formatting that {@code
 * Instant} back through {@link com.workin.legacy.LegacyClock}'s offset
 * produced {@code 2025-04-01 12:00:00} for a row stored as
 * {@code 2025-04-01 08:00:00} -- Hibernate's conversion already carries an
 * offset, and re-applying {@code LegacyClock}'s own offset double-converts.
 * Re-reading the row through {@link LegacyJdbcValues#rowMapper()} instead
 * (the same mechanism every other Phase-1 module uses) sidesteps the
 * conversion entirely: the driver hands back the stored text unchanged.
 */
@RestController
@RequestMapping("/apis/api/attendance_exception_types")
public class LegacyExceptionTypeController {

	private final LegacyExceptionTypeService service;
	private final LegacyRequestGuard guard;
	private final LegacyHrPermissionEnforcer hrPermissionEnforcer;
	private final LegacyMessages messages;
	private final JdbcTemplate jdbcTemplate;

	public LegacyExceptionTypeController(
			LegacyExceptionTypeService service, LegacyRequestGuard guard,
			LegacyHrPermissionEnforcer hrPermissionEnforcer, LegacyMessages messages, DataSource legacyDataSource) {
		this.service = service;
		this.guard = guard;
		this.hrPermissionEnforcer = hrPermissionEnforcer;
		this.messages = messages;
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	@RequestMapping("/list.php")
	public LegacyApiResponse list(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = readGuard();
		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());
		LegacyPagination.Params page = LegacyPagination.params(query);

		Object rawIsActive = query.value("is_active");
		Integer isActiveFilter = rawIsActive == null ? null : (int) LegacyValues.toPhpLong(rawIsActive);
		String search = LegacyPagination.searchQueryParam(query);

		LegacyExceptionTypePage result = service.list(
				context.companyId(), context.role(), isActiveFilter, search, (int) page.page(), (int) page.limit());

		List<Map<String, Object>> rows = result.data().stream().map(row -> wireRow(row.id())).toList();
		Map<String, Object> meta = LegacyPagination.meta(result.total(), page);
		return LegacyApiResponse.ok(message(request, "ok"), rows, meta);
	}

	@RequestMapping("/one.php")
	public LegacyApiResponse one(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = readGuard();
		long id = requiredId(request);
		service.one(context.companyId(), id);
		return LegacyApiResponse.ok(message(request, "ok"), wireRow(id));
	}

	@RequestMapping("/create.php")
	public ResponseEntity<LegacyApiResponse> create(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = writeGuard();
		Map<String, Object> body = LegacyJsonBody.read(request);
		String name = requireField(body, "name");
		LegacyExceptionTypeView created = service.create(context.companyId(), name, body.get("is_active"));
		return ResponseEntity.status(201).body(LegacyApiResponse.ok(message(request, "ok"), wireRow(created.id())));
	}

	@RequestMapping("/update.php")
	public LegacyApiResponse update(HttpServletRequest request) {
		requireMethod(request, "PUT");
		LegacyRequestContext context = writeGuard();
		long id = requiredId(request);
		Map<String, Object> body = LegacyJsonBody.read(request);
		service.update(context.companyId(), id, body);
		return LegacyApiResponse.ok(message(request, "ok"), wireRow(id));
	}

	@RequestMapping("/delete.php")
	public LegacyApiResponse delete(HttpServletRequest request) {
		requireMethod(request, "DELETE");
		LegacyRequestContext context = writeGuard();
		service.delete(context.companyId(), requiredId(request));
		return LegacyApiResponse.ok(message(request, "ok"), null);
	}

	/** list.php/one.php: bare {@code requireAuth()}, then {@code requireCompanyActive} -- no role restriction. */
	private LegacyRequestContext readGuard() {
		LegacyRequestContext context = guard.requireAuth();
		guard.requireCompanyActive(context.companyId());
		return context;
	}

	/** create/update/delete share this exact gate sequence (P-7/P-8, P-9, then P-3/D-045). */
	private LegacyRequestContext writeGuard() {
		LegacyRequestContext context = guard.requireAuth(LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR);
		guard.requireCompanyActive(context.companyId());
		hrPermissionEnforcer.require(LegacyHrPermissionKey.CAN_COMPANY_SETTINGS);
		return context;
	}

	/**
	 * {@code public_row($exception_type)}: every column, verbatim legacy names and values, read fresh
	 * (see the class javadoc) rather than through the JPA view's {@code Instant} fields.
	 */
	private Map<String, Object> wireRow(long id) {
		return jdbcTemplate.queryForObject(
				"SELECT * FROM exception_types WHERE id=?", LegacyJdbcValues.rowMapper(), id);
	}

	private static long requiredId(HttpServletRequest request) {
		Object id = LegacyQueryParameters.parse(request.getQueryString()).value("id");
		if (id == null || "".equals(id)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "id"));
		}
		return LegacyValues.toPhpLong(id);
	}

	/** {@code required($body, [Request::NAME])}: present-and-non-null, the field-required 400 either way. */
	private static String requireField(Map<String, Object> body, String field) {
		Object value = body.get(field);
		if (value == null || "".equals(value)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", field));
		}
		return LegacyValues.toPhpString(value);
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
