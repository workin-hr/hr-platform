package com.workin.legacy.employees;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

/**
 * The employee module on legacy's own URL surface (D-074):
 * {@code /apis/api/employees/*.php}, the PHP envelope, PHP's message keys.
 * Not {@code /api/legacy/employees} -- D-074 records that shape as
 * implementation drift, so this wave does not extend it.
 *
 * <p>Guard order is PHP's, at every endpoint:
 * {@code requireAuth([roles]); requireCompanyActive($company_id);} then the
 * endpoint's own {@code required()} checks. {@link LegacyRequestGuard} supplies
 * P-7 ({@code token_version}), P-8 (role) and P-9 (active company); the tenant
 * re-derivation behind {@link LegacyRequestContext#companyId()} is Phase 1's own
 * addition (ADR-0012), not legacy's.
 */
@RestController
@RequestMapping("/apis/api/employees")
public class LegacyEmployeeController {

	private final LegacyEmployeeService employeeService;
	private final LegacyRequestGuard requestGuard;
	private final LegacyMessages messages;

	public LegacyEmployeeController(
			LegacyEmployeeService employeeService, LegacyRequestGuard requestGuard, LegacyMessages messages) {
		this.employeeService = employeeService;
		this.requestGuard = requestGuard;
		this.messages = messages;
	}

	/** {@code employees/list.php}: {@code ok(EMPLOYEES, public_rows($rows), 200, [], pagination_meta(...))}. */
	@RequestMapping("/list.php")
	public LegacyApiResponse list(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = management();
		LegacyEmployeeService.Page page = employeeService.list(
				context, LegacyQueryParameters.parse(request.getQueryString()));
		return LegacyApiResponse.ok(message(request, "employees"), page.rows(), page.meta());
	}

	/** {@code employees/one.php}: {@code ok(EMPLOYEE_PROFILE, public_row($employee))}. */
	@RequestMapping("/one.php")
	public LegacyApiResponse one(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = management();
		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());
		requireId(query);
		Map<String, Object> employee = employeeService.one(context, LegacyValues.toPhpLong(query.value("id")));
		return LegacyApiResponse.ok(message(request, "employee_profile"), employee);
	}

	/**
	 * {@code employees/create.php}: POST, admin/HR, then the whole validation
	 * chain, one transaction, and a post-commit re-read rendered as 201.
	 */
	@RequestMapping("/create.php")
	public ResponseEntity<LegacyApiResponse> create(
			HttpServletRequest request, @RequestBody(required = false) Map<String, Object> body) {
		requireMethod(request, "POST");
		LegacyRequestContext context = administrative();
		Map<String, Object> employee = employeeService.create(context, body == null ? Map.of() : body);
		return ResponseEntity.status(201)
				.body(LegacyApiResponse.ok(message(request, "employee_created"), employee));
	}

	/** {@code employees/deactivate.php}: DELETE, admin/HR, then the scoped write and the notification. */
	@RequestMapping("/deactivate.php")
	public LegacyApiResponse deactivate(HttpServletRequest request) {
		requireMethod(request, "DELETE");
		LegacyRequestContext context = administrative();
		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());
		requireId(query);
		Map<String, Object> employee = employeeService.deactivate(
				context, LegacyValues.toPhpLong(query.value("id")),
				message(request, "notif_employee_deactivated_title"),
				message(request, "notif_employee_deactivated_body"));
		return LegacyApiResponse.ok(message(request, "employee_deactivated"), employee);
	}

	/** {@code employees/reactivate.php}: PUT, admin/HR, no notification. */
	@RequestMapping("/reactivate.php")
	public LegacyApiResponse reactivate(HttpServletRequest request) {
		requireMethod(request, "PUT");
		LegacyRequestContext context = administrative();
		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());
		requireId(query);
		Map<String, Object> employee = employeeService.reactivate(
				context, LegacyValues.toPhpLong(query.value("id")));
		return LegacyApiResponse.ok(message(request, "employee_reactivated"), employee);
	}

	/**
	 * Every method-guarded endpoint opens with
	 * {@code if ($_SERVER['REQUEST_METHOD'] !== HttpMethod::GET) fail(INVALID_METHOD, 405);}
	 * -- an ordinary first statement, not framework routing. Reproduced the same
	 * way: the routes are mapped for all methods and check inside, because
	 * Spring's own 405 is raised before a handler is chosen, which would leave it
	 * outside this module's advice and render the platform error body instead of
	 * the PHP envelope. {@code template_excel.php}, which has no guard at all,
	 * then needs no exception to this rule.
	 */
	private static void requireMethod(HttpServletRequest request, String expected) {
		if (!expected.equals(request.getMethod())) {
			throw new LegacyApiException(405, "invalid_method");
		}
	}

	/**
	 * {@code requireAuth([COMPANY_ADMIN, HR]); requireCompanyActive($company_id);}
	 * -- the write-side role list, which drops MANAGER. Neither lifecycle
	 * endpoint gates on an {@code hr_permissions} flag: only
	 * {@code employees/update.php} does, and only for an HR session changing
	 * somebody else (D-045/D-057's per-endpoint rule, re-checked here rather
	 * than assumed).
	 */
	private LegacyRequestContext administrative() {
		LegacyRequestContext context = requestGuard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR);
		requestGuard.requireCompanyActive(context.companyId());
		return context;
	}

	/** {@code requireAuth([COMPANY_ADMIN, HR, MANAGER]); requireCompanyActive($company_id);}. */
	private LegacyRequestContext management() {
		LegacyRequestContext context = requestGuard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR, LegacyEmployee.Role.MANAGER);
		requestGuard.requireCompanyActive(context.companyId());
		return context;
	}

	/**
	 * {@code required($_GET, [Request::ID])} ({@code functions.php:617-623}):
	 * missing, {@code null} and the exact empty string fail; {@code '0'} passes
	 * {@code required()} and only then casts to {@code 0}. The failure carries
	 * the field name as a {@code {field}} <em>placeholder</em> in the message --
	 * {@code fail(FIELD_REQUIRED, 400, null, [Response::FIELD =&gt; $field])} passes
	 * it as {@code $replace}, not as {@code $data}, so the response body has no
	 * {@code data} key at all.
	 */
	private static void requireId(LegacyQueryParameters query) {
		Object id = query.value("id");
		if (id == null || "".equals(id)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "id"));
		}
	}

	private String message(HttpServletRequest request, String key) {
		return messages.translate(messages.resolveLocale(request), key, null);
	}

}
