package com.workin.legacy.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.workin.legacy.payroll.LegacyPayrollFiscalSettings;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.attendance.location.LegacyAttendanceLocation;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.auth.LegacyRefreshTokenService;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.auth.otp.LegacyOtpAuthStore;
import com.workin.legacy.auth.otp.LegacyOtpService;
import com.workin.legacy.authorization.LegacyHrPermissionRows;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.employees.LegacyEmployeeStore;
import com.workin.legacy.notifications.LegacyNotifications;
import com.workin.legacy.phone.LegacyPhoneNumbers;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.http.HttpServletRequest;

/** The {@code apis/api/profile/*.php} endpoints ported in Wave 13.2. */
@Service
public class LegacyProfileService {

	/**
	 * {@code profile/employee.php}'s allow-list. Self-service means these five
	 * columns and nothing else -- role, branch, department, salary and the
	 * active flag are all unreachable from this endpoint no matter what the
	 * body carries.
	 */
	private static final List<String> SELF_SERVICE_COLUMNS =
			List.of("first_name", "last_name", "phone", "country_code", "address");

	/** {@code NotificationTypeEnum::EMPLOYEE_LEFT_COMPANY}. */
	private static final String EMPLOYEE_LEFT_COMPANY = "employee_left_company";

	private final LegacyProfileStore store;
	private final LegacyEmployeeStore employeeStore;
	private final LegacyHrPermissionRows permissionRows;
	private final LegacyAttendanceLocation attendanceLocation;
	/** month_start_day / month_end_day on the returned employee row. */
	private final LegacyPayrollFiscalSettings fiscalSettings;
	private final LegacyCompanyDelete companyDelete;
	private final LegacyNotifications notifications;
	private final PasswordEncoder passwordEncoder;
	private final LegacyMessages messages;
	private final LegacyPhoneNumbers phoneNumbers;
	private final LegacyOtpService otpService;
	private final LegacyOtpAuthStore otpAuthStore;
	private final LegacyRequestGuard requestGuard;
	private final LegacyRefreshTokenService refreshTokens;

	public LegacyProfileService(
			LegacyProfileStore store, LegacyEmployeeStore employeeStore,
			LegacyHrPermissionRows permissionRows, LegacyAttendanceLocation attendanceLocation,
			LegacyCompanyDelete companyDelete, LegacyNotifications notifications,
			PasswordEncoder passwordEncoder, LegacyMessages messages,
			LegacyPhoneNumbers phoneNumbers, LegacyOtpService otpService,
			LegacyOtpAuthStore otpAuthStore, LegacyRequestGuard requestGuard,
			LegacyRefreshTokenService refreshTokens,
			LegacyPayrollFiscalSettings fiscalSettings) {
		this.store = store;
		this.employeeStore = employeeStore;
		this.permissionRows = permissionRows;
		this.attendanceLocation = attendanceLocation;
		this.companyDelete = companyDelete;
		this.notifications = notifications;
		this.passwordEncoder = passwordEncoder;
		this.messages = messages;
		this.phoneNumbers = phoneNumbers;
		this.otpService = otpService;
		this.otpAuthStore = otpAuthStore;
		this.requestGuard = requestGuard;
		this.refreshTokens = refreshTokens;
		this.fiscalSettings = fiscalSettings;
	}

	// ---------------- profile/employee.php ----------------

	/**
	 * The GET half. The three {@code employee_row_attach_*} calls run in PHP's
	 * order and each mutates the row, so the result depends on that order:
	 * the permission attach removes the {@code can_*} columns before the
	 * location attach reads {@code can_check_in_any_branch} -- which is a
	 * different column and therefore survives.
	 */
	public Map<String, Object> employeeProfile(long employeeId, long companyId) {
		Map<String, Object> employee = store.profileForGet(employeeId, companyId);
		if (employee == null) {
			throw new LegacyApiException(404, "employee_not_found");
		}
		return attachAll(employee, companyId);
	}

	/**
	 * The PUT half.
	 *
	 * <p>Order is the contract and it is unusual: an empty body is rejected
	 * <em>before</em> the employee is looked up, the phone is validated before
	 * the allow-list is walked, and "no updatable field" is a second, later
	 * {@code nothing_to_update} that a body of unknown keys reaches.
	 */
	public Map<String, Object> updateEmployeeProfile(long employeeId, long companyId, Map<String, Object> body) {
		if (body == null || body.isEmpty()) {
			throw new LegacyApiException(400, "nothing_to_update");
		}
		if (store.employeeInCompany(employeeId, companyId) == null) {
			throw new LegacyApiException(404, "employee_not_found");
		}

		Map<String, Object> resolved = new LinkedHashMap<>(body);
		if (resolved.containsKey("phone")) {
			applyPhoneChange(resolved, employeeId);
		}

		List<String> assignments = new ArrayList<>();
		List<Object> binds = new ArrayList<>();
		for (String column : SELF_SERVICE_COLUMNS) {
			if (!resolved.containsKey(column)) {
				continue;
			}
			assignments.add(column + "=?");
			binds.add(LegacyValues.toPdoBindValue(resolved.get(column)));
		}

		// !empty($body[PASSWORD]) && is_string(...) && trim(...) !== ''
		// -- three tests, and the middle one is why a numeric password is
		// silently ignored rather than hashed.
		Object password = resolved.get("password");
		if (!LegacyValues.isPhpEmpty(password) && password instanceof String text
				&& !LegacyValues.phpTrim(text).isEmpty()) {
			assignments.add("password_hash=?");
			binds.add(passwordEncoder.encode(LegacyValues.phpTrim(text)));
		}

		if (assignments.isEmpty()) {
			throw new LegacyApiException(400, "nothing_to_update");
		}

		store.updateEmployee(assignments, binds, employeeId, companyId);
		return attachAll(store.profileAfterUpdate(employeeId, companyId), companyId);
	}

	/**
	 * The phone block of the PUT.
	 *
	 * <p>{@code normalize_employee_phone()} reduces the input to digits and
	 * turns an empty result into null, so {@code "phone": "abc"} clears the
	 * phone rather than failing. Clearing it also nulls {@code country_code}
	 * <b>whether or not the body mentioned it</b> -- the pair is kept
	 * consistent by force.
	 *
	 * <p>The {@code country_code} check is an {@code elseif}: it only runs when
	 * a phone survived normalisation <em>and</em> the body carried the key. A
	 * phone supplied without any country code at all is accepted here, unlike
	 * {@code resolve_employee_phone_and_country_code()} elsewhere in legacy.
	 */
	private void applyPhoneChange(Map<String, Object> body, long employeeId) {
		String normalized = normalizeEmployeePhone(body.get("phone"));
		if (normalized != null && employeeStore.phoneExistsGlobally(normalized, employeeId)) {
			throw new LegacyApiException(409, "phone_already_exists");
		}
		body.put("phone", normalized);
		if (normalized == null) {
			body.put("country_code", null);
			return;
		}
		if (body.containsKey("country_code")) {
			String code = LegacyValues.phpTrim(LegacyValues.toPhpString(body.get("country_code")));
			if (code.isEmpty()) {
				throw new LegacyApiException(400, "field_required", null, Map.of("field", "country_code"));
			}
			body.put("country_code", code);
		}
	}

	/** {@code normalize_employee_phone()} ({@code functions.php:70-73}). */
	private static String normalizeEmployeePhone(Object raw) {
		String digits = LegacyPhoneNumbers.digitsOnly(
				LegacyValues.phpTrim(raw == null ? "" : LegacyValues.toPhpString(raw)));
		return digits.isEmpty() ? null : digits;
	}

	private Map<String, Object> attachAll(Map<String, Object> employee, long companyId) {
		if (employee == null) {
			return null;
		}
		permissionRows.attach(employee);
		employeeStore.attachLatestSalaryContract(employee);
		attendanceLocation.attachBranchLocationConfiguredFlag(employee, companyId);
		fiscalSettings.attachCompanyFiscalMonth(employee, companyId);
		return employee;
	}

	// ---------------- profile/company.php ----------------

	/**
	 * {@code ['company' => ..., 'employee' => ...]}, where the second key is
	 * present only for an employee-type session whose employee row exists in
	 * that company. A company-type session gets the company alone, and so does
	 * an employee-type session whose row has since been moved or removed --
	 * the endpoint does not fail in that case, it simply omits the key.
	 */
	public Map<String, Object> companyProfile(LegacyRequestContext context) {
		Map<String, Object> company = store.company(context.companyId());
		if (company == null) {
			throw new LegacyApiException(404, "not_found");
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("company", com.workin.legacy.LegacyPublicRow.of(company));

		if ("employee".equals(context.authType()) && context.employeeId() > 0) {
			Map<String, Object> employee =
					store.companyProfileEmployee(context.employeeId(), context.companyId());
			if (employee != null) {
				permissionRows.attach(employee);
				payload.put("employee", com.workin.legacy.LegacyPublicRow.of(employee));
			}
		}
		return payload;
	}

	// ---------------- profile/change_password.php ----------------

	/**
	 * {@code change_password.php}. The account the password belongs to is
	 * chosen by auth type: a company session changes the <em>company</em>
	 * password and anything else changes the employee's.
	 *
	 * <p>{@code strlen()} counts bytes, not characters, so a six-character
	 * Arabic password is twelve bytes and passes while a five-character one is
	 * ten and also passes. That is legacy's rule and
	 * {@link String#getBytes} reproduces it; {@code String.length()} would not.
	 *
	 * <p>A wrong old password is <b>401</b>, not 403 -- the same status as no
	 * token at all.
	 */
	public void changePassword(LegacyRequestContext context, Map<String, Object> body) {
		required(body, "old_password", "new_password");
		String newPassword = LegacyValues.toPhpString(body.get("new_password"));
		if (newPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 6) {
			throw new LegacyApiException(400, "password_min_length");
		}
		String oldPassword = LegacyValues.toPhpString(body.get("old_password"));

		boolean company = context.isCompanyAuth();
		long id = company ? context.companyId() : context.employeeId();
		if (id == 0) {
			throw new LegacyApiException(401, "unauthorized");
		}
		String hash = company ? store.companyPasswordHash(id) : store.employeePasswordHash(id);
		if (!passwordMatches(oldPassword, hash)) {
			throw new LegacyApiException(401, "old_password_incorrect");
		}
		String encoded = passwordEncoder.encode(newPassword);
		if (company) {
			store.updateCompanyPassword(id, encoded);
		} else {
			store.updateEmployeePassword(id, encoded);
			// ADR-0005, same rule as auth/reset_password.php -- and a no-op on
			// this surface by design: D-111 forbids refresh tokens on the
			// literal Phase-1 /apis/** contract, so nothing here issues one.
			// See LegacyOtpAuthService#resetPassword and D-138.
			refreshTokens.revokeAllForEmployee(id);
		}
	}

	// ---------------- profile/logout.php ----------------

	/**
	 * {@code logout.php}, which does considerably more than log out: for a
	 * <em>regular employee</em> leaving the <em>mobile app</em> it
	 * <b>deactivates the account</b>, notifies the company, and drops every
	 * push token the employee owns. Re-joining needs the company code again.
	 *
	 * <h2>Three conditions, not one</h2>
	 * <p>The deactivation used to hang on the session type alone. It now needs
	 * all of:
	 * <ul>
	 * <li>an employee-app session -- {@code employee}, or any type that is not
	 *     {@code company} but carries an employee id;</li>
	 * <li>the {@code EMPLOYEE} role, so an HR, manager or admin session is
	 *     never deactivated by its own logout;</li>
	 * <li>a mobile {@code platform}, or none at all -- an absent field is
	 *     still the mobile case, because the older clients that predate the
	 *     field are the mobile app.</li>
	 * </ul>
	 * <p>A desktop client that names its platform is therefore left active,
	 * which is the point: {@code platform=desktop} is how the HR desktop app
	 * logs out without locking its own user out of the account.
	 *
	 * <p>The notification is sent only when the row was active <em>before</em>
	 * the update, so a repeated logout notifies once. The name falls back from
	 * the display name to the phone to {@code #id}, each after a trim, so the
	 * company never sees an empty name.
	 *
	 * <p>None of this is transactional in legacy and it is not here either: the
	 * deactivation is committed before the notification is attempted, so a
	 * failing notification leaves the account deactivated (D-089).
	 *
	 * <p>The notification text is rendered in the <b>logging-out employee's</b>
	 * locale, because {@code t()} reads the current request's language and this
	 * is that request. The company reading its inbox later sees whatever
	 * language the departing employee's app happened to be set to. That is
	 * legacy's behaviour for every notification it writes, not a quirk of this
	 * endpoint.
	 */
	public void logout(LegacyRequestContext context, Map<String, Object> body, String locale) {
		Object token = body == null ? null : body.get("token");
		if (!LegacyValues.isPhpEmpty(token) && context.employeeId() > 0) {
			store.deletePushToken(LegacyValues.toPhpString(token), context.employeeId());
		}

		Object rawPlatform = body == null ? null : body.get("platform");
		String platform = LegacyValues.mbStrToLower(LegacyValues.phpTrim(
				rawPlatform == null ? "" : LegacyValues.toPhpString(rawPlatform)));

		boolean employeeAppSession = "employee".equals(context.authType())
				|| (!"company".equals(context.authType()) && context.employeeId() > 0);
		boolean regularEmployee = context.role() == LegacyEmployee.Role.EMPLOYEE;
		boolean mobileClient = MOBILE_PLATFORMS.contains(platform);

		if (!employeeAppSession || !regularEmployee
				|| context.employeeId() <= 0 || context.companyId() <= 0
				|| !(mobileClient || platform.isEmpty())) {
			return;
		}

		Map<String, Object> employee = store.employeeForLogout(context.employeeId(), context.companyId());
		boolean wasActive = employee != null && LegacyValues.toPhpLong(employee.get("is_active")) == 1;

		store.deactivateAndRevokeSessions(context.employeeId(), context.companyId());

		if (wasActive) {
			notifications.toCompany(context.companyId(), context.employeeId(), EMPLOYEE_LEFT_COMPANY,
					messages.translate(locale, "notif_employee_left_company_title", null),
					messages.translate(locale, "notif_employee_left_company_body",
							Map.of("employee", leaverName(employee, context.employeeId()))),
					"employee", context.employeeId());
		}

		store.deletePushTokensForEmployee(context.employeeId());
		// ADR-0005: logout revokes the session. Legacy's own "logout" bumps
		// nothing -- it deactivates the account instead. A no-op on this
		// surface for the same D-111 reason as the two password paths.
		refreshTokens.revokeAllForEmployee(context.employeeId());
	}

	/** {@code in_array($platform, ['mobile', 'android', 'ios'], true)}. */
	private static final java.util.Set<String> MOBILE_PLATFORMS =
			java.util.Set.of("mobile", "android", "ios");

	private static String leaverName(Map<String, Object> employee, long employeeId) {
		String name = LegacyValues.phpTrim(LegacyValues.toPhpString(employee.get("employee_name")));
		if (name.isEmpty()) {
			name = LegacyValues.phpTrim(LegacyValues.toPhpString(employee.get("phone")));
		}
		return name.isEmpty() ? "#" + employeeId : name;
	}

	// ---------------- profile/register_push_token.php ----------------

	/**
	 * {@code register_push_token.php}. Exactly one of the two owner columns is
	 * set and the other is SQL NULL, chosen by auth type -- a token is owned by
	 * an employee or by a company, never by both.
	 *
	 * <p>The failure message here is a literal English string in legacy,
	 * {@code fail('Invalid user', 400)}, not a translation key. It is passed
	 * through unchanged rather than promoted to a key, because clients that
	 * match on the message would stop matching.
	 */
	public void registerPushToken(LegacyRequestContext context, Map<String, Object> body) {
		required(body, "token", "platform");
		boolean employeeAuth = "employee".equals(context.authType());
		Long employeeId = employeeAuth && context.employeeId() != 0 ? context.employeeId() : null;
		Long companyId = context.isCompanyAuth() && context.companyId() != 0 ? context.companyId() : null;
		if (employeeId == null && companyId == null) {
			throw new LegacyApiException(400, "Invalid user");
		}
		store.upsertPushToken(employeeId, companyId,
				LegacyValues.toPdoBindValue(body.get("token")),
				LegacyValues.toPdoBindValue(body.get("platform")));
	}

	// ---------------- profile/delete_account*.php ----------------

	/** {@code delete_account_preview.php}: company sessions only, 401 otherwise. */
	public Map<String, Object> deleteAccountPreview(LegacyRequestContext context, String locale) {
		if (!context.isCompanyAuth() || context.companyId() <= 0) {
			throw new LegacyApiException(401, "unauthorized");
		}
		if (!store.companyExists(context.companyId())) {
			throw new LegacyApiException(404, "not_found");
		}
		return companyDelete.previewPayload(context.companyId(), locale);
	}

	/** {@code delete_account.php}'s two cases, distinguished by auth type. */
	public sealed interface DeleteResult {
		/** The company was hard-deleted; the pre-delete summary comes back. */
		record CompanyDeleted(List<Map<String, Object>> deletedRelated) implements DeleteResult {
		}

		/** The employee was deactivated; {@code ok(OK)} with no data. */
		record EmployeeDeactivated() implements DeleteResult {
		}
	}

	/**
	 * {@code delete_account.php}.
	 *
	 * <p>A wrong password is {@code invalid_phone_password} with 401 in both
	 * branches -- not {@code old_password_incorrect}, which is
	 * {@code change_password.php}'s. An auth type that is neither
	 * {@code company} nor {@code employee} falls off the end of the file and
	 * gets a bare 401.
	 */
	public DeleteResult deleteAccount(LegacyRequestContext context, Map<String, Object> body, String locale) {
		required(body, "password");
		String password = LegacyValues.toPhpString(body.get("password"));

		if (context.isCompanyAuth()) {
			if (context.companyId() == 0) {
				throw new LegacyApiException(401, "unauthorized");
			}
			if (!passwordMatches(password, store.companyPasswordHash(context.companyId()))) {
				throw new LegacyApiException(401, "invalid_phone_password");
			}
			return new DeleteResult.CompanyDeleted(
					companyDelete.cascadeDelete(context.companyId(), locale));
		}

		if ("employee".equals(context.authType())) {
			if (context.employeeId() == 0 || context.companyId() == 0) {
				throw new LegacyApiException(401, "unauthorized");
			}
			if (!passwordMatches(password,
					store.employeePasswordHashInCompany(context.employeeId(), context.companyId()))) {
				throw new LegacyApiException(401, "invalid_phone_password");
			}
			store.deactivate(context.employeeId(), context.companyId());
			store.deletePushTokensForEmployee(context.employeeId());
			return new DeleteResult.EmployeeDeactivated();
		}

		throw new LegacyApiException(401, "unauthorized");
	}

	// ---------------- profile/{request,confirm}_phone_change.php ----------------

	/**
	 * The five checks both phone-change routes share, in PHP's order.
	 *
	 * <p>Both files repeat this block verbatim, so it is written once here and
	 * the order is preserved exactly: resolve the dial code, normalise the
	 * number, reject an invalid one, reject one equivalent to the company's
	 * current number, then reject one another company already holds.
	 *
	 * <p>The same-as-current test uses {@code phones_are_equivalent()} while
	 * the uniqueness test uses {@code phone_sql_match_clause()} -- two
	 * different mechanisms for the same question, both variant-aware, and both
	 * kept because they disagree at the edges: an empty stored phone is
	 * skipped by the first ({@code $currentPhone !== ''}) but would still be
	 * compared by the second.
	 *
	 * @return the normalised phone and the resolved dial code
	 */
	private String[] validatedNewCompanyPhone(long companyId, Map<String, Object> body) {
		required(body, "phone", "country_code");
		String countryCode = phoneNumbers.resolveCode(
				LegacyValues.phpTrim(LegacyValues.toPhpString(body.get("country_code"))));
		String phone = phoneNumbers.normalizeLocal(countryCode,
				LegacyValues.phpTrim(LegacyValues.toPhpString(body.get("phone"))));

		if (phone.isEmpty() || !phoneNumbers.isValidLocal(countryCode, phone)) {
			throw new LegacyApiException(400, "invalid_phone_number");
		}

		String current = LegacyValues.phpTrim(
				LegacyValues.toPhpString(otpAuthStore.companyPhone(companyId)));
		if (!current.isEmpty() && LegacyPhoneNumbers.areEquivalent(current, phone)) {
			throw new LegacyApiException(400, "phone_same_as_current");
		}

		if (otpAuthStore.anotherCompanyHasPhone(phone, companyId)) {
			throw new LegacyApiException(409, "phone_already_registered");
		}
		return new String[] { phone, countryCode };
	}

	/**
	 * {@code profile/request_phone_change.php}.
	 *
	 * <p>Company sessions only, and the refusal is <b>403 {@code forbidden}</b>
	 * -- not the 401 {@code delete_account_preview.php} uses for the same
	 * condition. Two routes in one module disagreeing about the status for
	 * "wrong session type" is legacy's, and both are preserved.
	 *
	 * <p>It checks {@code otp_has_recent_for_phone()} itself and answers
	 * <b>429</b>, then {@code otp_issue_and_send_whatsapp()} checks the same
	 * window again inside the limiter. The first check wins, so the status is
	 * 429 either way here -- unlike {@code resend_otp.php}, where the
	 * equivalent local check answers 400.
	 */
	public void requestPhoneChange(
			HttpServletRequest request, LegacyRequestContext context, Map<String, Object> body, String locale) {
		requireCompanySession(context);
		requestGuard.requireCompanyActive(context.companyId());
		String[] resolved = validatedNewCompanyPhone(context.companyId(), body);

		if (otpService.hasRecentForPhone(resolved[0], 60)) {
			throw new LegacyApiException(429, "please_wait_before_resending");
		}
		otpService.issueAndSendWhatsApp(
				request, resolved[0], LegacyOtpService.SMS_OTP_VERIFY, resolved[1], 10, locale);
	}

	/**
	 * {@code profile/confirm_phone_change.php}.
	 *
	 * <p>{@code required($body, [PHONE, COUNTRY_CODE, OTP])} is a single call
	 * over three fields and runs before any of them is validated, so its order
	 * decides which missing field a half-empty body is told about. Then the
	 * same five checks, then the OTP, then the write. Note what the
	 * write does beyond the phone: it sets {@code otp_verified = 1}, so a
	 * company that had never verified its original number becomes verified by
	 * changing it. And the OTP is cleared <b>after</b> the update, so a failure
	 * between them would leave the code reusable against the new number.
	 *
	 * @return the whole company row, as {@code public_row($row)}
	 */
	public Map<String, Object> confirmPhoneChange(LegacyRequestContext context, Map<String, Object> body) {
		requireCompanySession(context);
		requestGuard.requireCompanyActive(context.companyId());
		// One required() call over three fields, in PHP's order -- phone,
		// country_code, otp -- so a body missing both the phone and the code
		// reports the *phone*. Checking `otp` first, as an earlier draft did,
		// reported `otp` for that body and sent a compatibility client down the
		// wrong recovery flow.
		required(body, "phone", "country_code", "otp");
		String[] resolved = validatedNewCompanyPhone(context.companyId(), body);

		if (otpService.verifyLatestForPhone(resolved[0], body.get("otp")) == null) {
			throw new LegacyApiException(400, "invalid_expired_otp");
		}

		otpAuthStore.changeCompanyPhone(context.companyId(), resolved[0], resolved[1]);
		otpService.clearForPhone(resolved[0]);
		return com.workin.legacy.LegacyPublicRow.of(otpAuthStore.company(context.companyId()));
	}

	/** {@code if (($auth[TYPE] ?? '') !== COMPANY) fail(FORBIDDEN, 403);} */
	private static void requireCompanySession(LegacyRequestContext context) {
		if (!context.isCompanyAuth()) {
			throw new LegacyApiException(403, "forbidden");
		}
	}

	// ---------------- shared ----------------

	/** {@code password_verify()}: false for a missing, empty or unusable hash. */
	private boolean passwordMatches(String password, String hash) {
		try {
			return hash != null && !hash.isEmpty() && passwordEncoder.matches(password, hash);
		} catch (RuntimeException ex) {
			return false;
		}
	}

	/** {@code required($data, [$fields])} -- missing, null and "" fail; "0" passes. */
	private static void required(Map<String, Object> body, String... keys) {
		for (String key : keys) {
			Object value = body == null ? null : body.get(key);
			if (value == null || "".equals(value)) {
				throw new LegacyApiException(400, "field_required", null, Map.of("field", key));
			}
		}
	}
}
