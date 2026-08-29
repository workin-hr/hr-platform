package com.workin.legacy.planning;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.legacy.LegacyJsonBody;
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
 * {@code /apis/api/workforce_planning/*.php} (Wave 13.4b).
 *
 * <p>Seven routes, six handlers: {@code summary.php} is literally
 * {@code require __DIR__ . '/list.php'}, so the two URLs are the same endpoint
 * and must stay byte-identical. It is mapped as a second path on one method
 * rather than duplicated, which is the only arrangement that cannot drift.
 *
 * <p>Reads admit MANAGER; writes are COMPANY_ADMIN or HR. No
 * {@code hr_permissions} gate anywhere in the module.
 */
@RestController
@RequestMapping("/apis/api/workforce_planning")
public class LegacyWorkforcePlanningController {

	private final LegacyWorkforcePlanningService planningService;
	private final LegacyRequestGuard requestGuard;
	private final LegacyMessages messages;

	public LegacyWorkforcePlanningController(
			LegacyWorkforcePlanningService planningService, LegacyRequestGuard requestGuard,
			LegacyMessages messages) {
		this.planningService = planningService;
		this.requestGuard = requestGuard;
		this.messages = messages;
	}

	/** Both {@code list.php} and its {@code summary.php} alias. */
	@RequestMapping({"/list.php", "/summary.php"})
	public LegacyApiResponse list(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = readers();
		LegacyWorkforcePlanningService.Page page = planningService.list(
				context.companyId(), LegacyQueryParameters.parse(request.getQueryString()));
		return LegacyApiResponse.ok(message(request, "ok"), page.rows(), page.meta());
	}

	@RequestMapping("/one.php")
	public LegacyApiResponse one(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = readers();
		return LegacyApiResponse.ok(message(request, "ok"),
				planningService.one(context.companyId(), requiredId(request)));
	}

	@RequestMapping("/create.php")
	public ResponseEntity<LegacyApiResponse> create(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = writers();
		Map<String, Object> row =
				planningService.create(context.companyId(), LegacyJsonBody.read(request));
		return ResponseEntity.status(201).body(LegacyApiResponse.ok(message(request, "ok"), row));
	}

	/** {@code ok(OK, ['saved' => true])} -- 200, and never the row. */
	@RequestMapping("/save_target.php")
	public LegacyApiResponse saveTarget(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = writers();
		planningService.saveTarget(context.companyId(), LegacyJsonBody.read(request));
		return LegacyApiResponse.ok(message(request, "ok"), Map.of("saved", true));
	}

	@RequestMapping("/update.php")
	public LegacyApiResponse update(HttpServletRequest request) {
		requireMethod(request, "PUT");
		LegacyRequestContext context = writers();
		long id = requiredId(request);
		return LegacyApiResponse.ok(message(request, "ok"),
				planningService.update(context.companyId(), id, LegacyJsonBody.read(request)));
	}

	@RequestMapping("/delete.php")
	public LegacyApiResponse delete(HttpServletRequest request) {
		requireMethod(request, "DELETE");
		LegacyRequestContext context = writers();
		planningService.delete(context.companyId(), requiredId(request));
		return LegacyApiResponse.ok(message(request, "ok"), Map.of("deleted", true));
	}

	private LegacyRequestContext readers() {
		return active(LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR,
				LegacyEmployee.Role.MANAGER);
	}

	private LegacyRequestContext writers() {
		return active(LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR);
	}

	private LegacyRequestContext active(LegacyEmployee.Role... roles) {
		LegacyRequestContext context = requestGuard.requireAuth(roles);
		requestGuard.requireCompanyActive(context.companyId());
		return context;
	}

	private static void requireMethod(HttpServletRequest request, String expected) {
		if (!expected.equals(request.getMethod())) {
			throw new LegacyApiException(405, "invalid_method");
		}
	}

	private static long requiredId(HttpServletRequest request) {
		Object id = LegacyQueryParameters.parse(request.getQueryString()).value("id");
		if (id == null || "".equals(id)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "id"));
		}
		return LegacyValues.toPhpLong(id);
	}

	private String message(HttpServletRequest request, String key) {
		return messages.translate(messages.resolveLocale(request), key, null);
	}
}
