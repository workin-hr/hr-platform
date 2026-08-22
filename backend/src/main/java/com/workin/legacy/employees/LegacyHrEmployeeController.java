package com.workin.legacy.employees;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.legacy.LegacyJsonBody;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

/**
 * {@code /apis/api/hr_employees/*.php} -- the desktop system users.
 *
 * <p>Guard order is PHP's on all three: the method check first, then
 * {@code requireAuth([COMPANY_ADMIN, HR])}, then
 * {@code requireCompanyActive($company_id)}.
 *
 * <p>The two mutations then run D-076's authority check, and run it
 * <em>immediately</em> -- before the body is read, before the query id is read,
 * before any target is looked up. An HR session therefore gets one answer,
 * {@code forbidden} at 403, for every privileged mutation it attempts,
 * regardless of what it aimed at or whether that target exists.
 */
@RestController
@RequestMapping("/apis/api/hr_employees")
public class LegacyHrEmployeeController {

	private final LegacyHrEmployeeService hrEmployeeService;
	private final LegacyRequestGuard requestGuard;
	private final LegacyMessages messages;

	public LegacyHrEmployeeController(
			LegacyHrEmployeeService hrEmployeeService, LegacyRequestGuard requestGuard,
			LegacyMessages messages) {
		this.hrEmployeeService = hrEmployeeService;
		this.requestGuard = requestGuard;
		this.messages = messages;
	}

	/** {@code hr_employees/list.php}: GET, admin/HR, unchanged from PHP -- D-076 gates only the mutations. */
	@RequestMapping("/list.php")
	public LegacyApiResponse list(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = administrative();
		LegacyEmployeeService.Page page = hrEmployeeService.list(
				context, LegacyQueryParameters.parse(request.getQueryString()));
		return LegacyApiResponse.ok(message(request, "hr_users"), page.rows(), page.meta());
	}

	/** {@code hr_employees/create.php}: POST, admin only under D-076, then PHP's own chain. */
	@RequestMapping("/create.php")
	public ResponseEntity<LegacyApiResponse> create(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = administrative();
		// D-076, before the body is even read.
		hrEmployeeService.requireMutationAuthority(context);

		Map<String, Object> created = hrEmployeeService.create(context, LegacyJsonBody.read(request));
		return ResponseEntity.status(201)
				.body(LegacyApiResponse.ok(message(request, "hr_user_created"), created));
	}

	/** {@code hr_employees/update_permissions.php}: PUT, admin only under D-076, then PHP's own chain. */
	@RequestMapping("/update_permissions.php")
	public LegacyApiResponse updatePermissions(HttpServletRequest request) {
		requireMethod(request, "PUT");
		LegacyRequestContext context = administrative();
		// D-076, before the query id is even read.
		hrEmployeeService.requireMutationAuthority(context);

		Map<String, Object> updated = hrEmployeeService.updatePermissions(
				context, LegacyQueryParameters.parse(request.getQueryString()),
				LegacyJsonBody.read(request));
		return LegacyApiResponse.ok(message(request, "permissions_updated"), updated);
	}

	/**
	 * The opening line of each of the three files. Mapped for all methods and
	 * checked inside, for the reason {@link LegacyEmployeeController} records:
	 * Spring's own 405 is raised before a handler is chosen, which would put it
	 * outside this module's advice.
	 */
	private static void requireMethod(HttpServletRequest request, String expected) {
		if (!expected.equals(request.getMethod())) {
			throw new LegacyApiException(405, "invalid_method");
		}
	}

	/** {@code requireAuth([COMPANY_ADMIN, HR]); requireCompanyActive($company_id);}. */
	private LegacyRequestContext administrative() {
		LegacyRequestContext context = requestGuard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR);
		requestGuard.requireCompanyActive(context.companyId());
		return context;
	}

	private String message(HttpServletRequest request, String key) {
		return messages.translate(messages.resolveLocale(request), key, null);
	}

}
