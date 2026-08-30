package com.workin.legacy.auth.registration;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.legacy.LegacyJsonBody;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyPhpJwtService;
import com.workin.legacy.uploads.LegacyFileUploads;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;
import com.workin.legacy.wire.LegacyPostFields;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Wave 13.1b: the nine account-lifecycle routes of {@code apis/api/auth/}.
 *
 * <p>All nine are public. Three of them mint a token, and one of those three
 * -- {@code complete_company_registration.php} -- mints a <b>company-admin</b>
 * token for a {@code company_id} the caller supplies, with no possession proof
 * of any kind. That is R-016, ported in parity form and recorded rather than
 * silently fixed.
 */
@RestController
public class LegacyRegistrationController {

	private final LegacyRegistrationService service;
	private final LegacyPhpJwtService jwtService;
	private final LegacyEmployeeSessionTokens sessionTokens;
	private final LegacyFileUploads fileUploads;
	private final LegacyMessages messages;

	public LegacyRegistrationController(
			LegacyRegistrationService service, LegacyPhpJwtService jwtService,
			LegacyEmployeeSessionTokens sessionTokens, LegacyFileUploads fileUploads,
			LegacyMessages messages) {
		this.service = service;
		this.jwtService = jwtService;
		this.sessionTokens = sessionTokens;
		this.fileUploads = fileUploads;
		this.messages = messages;
	}

	/** The only GET in the module. */
	@RequestMapping("/apis/api/auth/get_company_registration_options.php")
	public LegacyApiResponse registrationOptions(HttpServletRequest request) {
		requireMethod(request, "GET");
		return LegacyApiResponse.ok(message(request, "ok"), service.registrationOptions());
	}

	@RequestMapping("/apis/api/auth/lookup_company.php")
	public LegacyApiResponse lookupCompany(HttpServletRequest request) {
		requireMethod(request, "POST");
		return LegacyApiResponse.ok(message(request, "ok"),
				service.lookupCompany(LegacyJsonBody.read(request)));
	}

	/** Four outcomes, all 200 -- it routes the client's next screen, it does not guard. */
	@RequestMapping("/apis/api/auth/check_status.php")
	public LegacyApiResponse checkStatus(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRegistrationService.StatusScreen screen =
				service.checkStatus(LegacyJsonBody.read(request), messages.resolveLocale(request));
		return LegacyApiResponse.ok(message(request, screen.messageKey()), screen.data());
	}

	@RequestMapping("/apis/api/auth/register_company.php")
	public ResponseEntity<LegacyApiResponse> registerCompany(HttpServletRequest request) {
		requireMethod(request, "POST");
		Map<String, Object> data = service.registerCompany(
				request, LegacyJsonBody.read(request), messages.resolveLocale(request));
		return ResponseEntity.status(201).body(
				LegacyApiResponse.ok(message(request, "company_registered_verify_otp"), data));
	}

	/**
	 * {@code complete_company_registration.php} -- multipart, and the only
	 * route in the module that reads {@code $_POST} rather than a JSON body.
	 *
	 * <p>The two files are resolved <b>before</b> the service is called and in
	 * PHP's own order (logo, then commercial register), because
	 * {@code uploadFile()} runs at that point in the file and its side effect
	 * -- writing to disk -- happens whether or not the later validation passes.
	 */
	@RequestMapping("/apis/api/auth/complete_company_registration.php")
	public ResponseEntity<LegacyApiResponse> completeRegistration(HttpServletRequest request) {
		requireMethod(request, "POST");

		LegacyRegistrationService.CompleteRegistrationInput input =
				new LegacyRegistrationService.CompleteRegistrationInput(
						postLong(request, "company_id"),
						LegacyCompanyCode.normalize(LegacyPostFields.field(request, "company_code")),
						postTrimmed(request, "company_name"),
						postTrimmed(request, "first_name"),
						postTrimmed(request, "last_name"),
						postTrimmed(request, "main_branch_address"),
						postLong(request, "company_title_id"),
						postLong(request, "company_activity_id"),
						postLong(request, "company_size_id"));

		// Suppliers, not values: uploadFile() writes to disk, and PHP does not
		// reach it until every gate above has passed. Java evaluates arguments
		// eagerly, so passing the stored URLs here would store both files for a
		// request that is about to be rejected.
		LegacyRegistrationService.UploadedFiles uploads =
				new LegacyRegistrationService.UploadedFiles() {
					@Override
					public String logoUrl() {
						return fileUploads.store(LegacyPostFields.file(request, "logo"), "logos");
					}

					@Override
					public String commercialRegUrl() {
						return fileUploads.store(
								LegacyPostFields.file(request, "commercial_reg"), "commercial");
					}
				};

		Map<String, Object> data =
				service.completeRegistration(input, uploads, messages.resolveLocale(request));

		// R-016: the token is minted from the caller-supplied company_id.
		Map<String, Object> payload = new LinkedHashMap<>(data);
		payload.put("token", jwtService.issueCompanyToken(input.companyId(), "company_admin"));
		return ResponseEntity.status(201).body(
				LegacyApiResponse.ok(message(request, "company_registration_completed"), payload));
	}

	@RequestMapping("/apis/api/auth/register_employee.php")
	public ResponseEntity<LegacyApiResponse> registerEmployee(HttpServletRequest request) {
		requireMethod(request, "POST");
		return ResponseEntity.status(201).body(LegacyApiResponse.ok(
				message(request, "joined_company_wait_hr"),
				service.registerEmployee(LegacyJsonBody.read(request))));
	}

	@RequestMapping("/apis/api/auth/join_company.php")
	public ResponseEntity<LegacyApiResponse> joinCompany(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRegistrationService.JoinResult result =
				service.joinCompany(LegacyJsonBody.read(request), messages.resolveLocale(request));

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("token", sessionTokens.issue(result.employeeId(), result.companyId(), "employee"));
		payload.put("employee", result.employee());
		return ResponseEntity.status(201).body(
				LegacyApiResponse.ok(message(request, "joined_company_wait_hr"), payload));
	}

	@RequestMapping("/apis/api/auth/login_company.php")
	public LegacyApiResponse loginCompany(HttpServletRequest request) {
		requireMethod(request, "POST");
		Map<String, Object> body = LegacyJsonBody.read(request);
		required(body, "phone", "password");
		return companyLoginResponse(request, body, false);
	}

	/**
	 * {@code login_desktop.php}. {@code login_as} chooses the branch and its
	 * accepted spellings are wider than they look: {@code hr} or
	 * {@code employee} take the HR branch, and {@code company},
	 * {@code company_admin} or {@code company} take the company branch --
	 * anything else is {@code field_required} naming {@code login_as}.
	 */
	@RequestMapping("/apis/api/auth/login_desktop.php")
	public LegacyApiResponse loginDesktop(HttpServletRequest request) {
		requireMethod(request, "POST");
		Map<String, Object> body = LegacyJsonBody.read(request);
		required(body, "phone", "password", "login_as");

		String loginAs = LegacyValues.phpTrim(LegacyValues.toPhpString(body.get("login_as")))
				.toLowerCase(java.util.Locale.ROOT);
		if ("hr".equals(loginAs) || "employee".equals(loginAs)) {
			return LegacyApiResponse.ok(message(request, "login_successful"),
					service.desktopHrLogin(body, messages.resolveLocale(request)));
		}
		if ("company".equals(loginAs) || "company_admin".equals(loginAs)) {
			return companyLoginResponse(request, body, true);
		}
		throw new LegacyApiException(400, "field_required", null, Map.of("field", "login_as"));
	}

	/** The three shapes a company login can answer with, shared by both routes. */
	private LegacyApiResponse companyLoginResponse(
			HttpServletRequest request, Map<String, Object> body, boolean desktop) {
		LegacyRegistrationService.CompanyLoginResult result =
				service.companyLogin(request, body, desktop, messages.resolveLocale(request));

		if (result instanceof LegacyRegistrationService.CompanyLoginResult.VerifyOtpFirst pending) {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("company", pending.company());
			payload.put("otp_required", true);
			return LegacyApiResponse.ok(message(request, "verify_otp_first"), payload);
		}
		if (result instanceof LegacyRegistrationService.CompanyLoginResult.ProfileIncomplete incomplete) {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("company", incomplete.company());
			return LegacyApiResponse.ok(message(request, "login_successful"), payload);
		}

		LegacyRegistrationService.CompanyLoginResult.Authenticated ok =
				(LegacyRegistrationService.CompanyLoginResult.Authenticated) result;
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("company", ok.company());
		payload.put("token", jwtService.issueCompanyToken(ok.companyId(), "company_admin"));
		return LegacyApiResponse.ok(message(request, "login_successful"), payload);
	}

	private static long postLong(HttpServletRequest request, String name) {
		String raw = LegacyPostFields.field(request, name);
		return raw == null ? 0L : LegacyValues.toPhpLong(raw);
	}

	private static String postTrimmed(HttpServletRequest request, String name) {
		String raw = LegacyPostFields.field(request, name);
		return LegacyValues.phpTrim(raw == null ? "" : raw);
	}

	private static void requireMethod(HttpServletRequest request, String expected) {
		if (!expected.equals(request.getMethod())) {
			throw new LegacyApiException(405, "invalid_method");
		}
	}

	private static void required(Map<String, Object> body, String... keys) {
		for (String key : keys) {
			Object value = body == null ? null : body.get(key);
			if (value == null || "".equals(value)) {
				throw new LegacyApiException(400, "field_required", null, Map.of("field", key));
			}
		}
	}

	private String message(HttpServletRequest request, String key) {
		return messages.translate(messages.resolveLocale(request), key, null);
	}
}
