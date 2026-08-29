package com.workin.legacy.auth.registration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyPublicRow;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.otp.LegacyOtpService;
import com.workin.legacy.authorization.LegacyHrPermissionRows;
import com.workin.legacy.employees.LegacyEmployeeStore;
import com.workin.legacy.notifications.LegacyNotifications;
import com.workin.legacy.phone.LegacyPhoneNumbers;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Wave 13.1b: the nine account-lifecycle endpoints of {@code apis/api/auth/}.
 *
 * <h2>Three different ways to find a company by phone, all preserved</h2>
 * <p>{@code register_company.php} and {@code register_employee.php} and
 * {@code login_company.php} match the {@code phone} column <b>exactly</b>
 * against the submitted value. {@code join_company.php} and
 * {@code forgot_password.php} match through
 * {@code phone_sql_match_clause()}, which accepts every stored spelling. The
 * consequence is real and asymmetric: a company stored as
 * {@code +201012345678} can be joined and can reset its password, but cannot
 * <b>log in</b> unless the client sends exactly that string, and a second
 * registration under {@code 01012345678} is not detected as a duplicate.
 * Every one of these is legacy's and none is harmonised (D-058).
 */
@Service
public class LegacyRegistrationService {

	private static final String ACTIVE = "active";
	private static final String PENDING = "pending";
	private static final String REJECTED = "rejected";
	private static final String SUSPENDED = "suspended";
	private static final String DEFAULT_COUNTRY_CODE = "+20";

	private final LegacyRegistrationStore store;
	private final LegacyEmployeeStore employeeStore;
	private final LegacyPhoneNumbers phoneNumbers;
	private final LegacyOtpService otp;
	private final LegacyNotifications notifications;
	private final PasswordEncoder passwordEncoder;
	private final LegacyMessages messages;
	private final LegacyEmployeeSessionTokens sessionTokens;
	private final LegacyHrPermissionRows permissionRows;

	public LegacyRegistrationService(
			LegacyRegistrationStore store, LegacyEmployeeStore employeeStore,
			LegacyPhoneNumbers phoneNumbers, LegacyOtpService otp,
			LegacyNotifications notifications, PasswordEncoder passwordEncoder, LegacyMessages messages,
			LegacyEmployeeSessionTokens sessionTokens, LegacyHrPermissionRows permissionRows) {
		this.store = store;
		this.employeeStore = employeeStore;
		this.phoneNumbers = phoneNumbers;
		this.otp = otp;
		this.notifications = notifications;
		this.passwordEncoder = passwordEncoder;
		this.messages = messages;
		this.sessionTokens = sessionTokens;
		this.permissionRows = permissionRows;
	}

	// ---------------- get_company_registration_options.php ----------------

	/** Three lookup lists, keyed exactly as PHP names them. */
	public Map<String, Object> registrationOptions() {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("activities", store.companyActivities());
		payload.put("titles", store.companyTitles());
		payload.put("sizes", store.companySizes());
		return payload;
	}

	// ---------------- lookup_company.php ----------------

	/**
	 * {@code lookup_company.php}.
	 *
	 * <p>Two ways in, and the fallback is only reached when
	 * {@code company_code} is <b>empty after normalisation</b>: a supplied but
	 * invalid code is {@code company_code_invalid} 400 and never falls back to
	 * the id. With neither, the error names {@code company_code} rather than
	 * {@code company_id}.
	 *
	 * <p>The two paths return different projections in PHP -- {@code SELECT *}
	 * by code, five columns by id -- but the response is built from the same
	 * six keys either way, so the difference is invisible on the wire.
	 */
	public Map<String, Object> lookupCompany(Map<String, Object> body) {
		String code = LegacyCompanyCode.normalize(body == null ? null : body.get("company_code"));
		Map<String, Object> row;
		if (code.isEmpty()) {
			long legacyId = body == null || body.get("company_id") == null
					? 0L : LegacyValues.toPhpLong(body.get("company_id"));
			if (legacyId <= 0) {
				throw new LegacyApiException(400, "field_required", null, Map.of("field", "company_code"));
			}
			row = store.findByIdForLookup(legacyId);
		} else {
			if (!LegacyCompanyCode.isValid(code)) {
				throw new LegacyApiException(400, "company_code_invalid");
			}
			row = store.findByPublicCode(code);
		}

		if (row == null) {
			throw new LegacyApiException(404, "company_code_not_found");
		}

		long companyId = LegacyValues.toPhpLong(row.get("id"));
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("company_id", companyId);
		payload.put("company_code", LegacyValues.toPhpString(row.get("company_code")));
		payload.put("company_name", LegacyValues.toPhpString(row.get("company_name")));
		// logo_url is the one key PHP does not cast, so a NULL stays null
		// instead of becoming "".
		payload.put("logo_url", row.get("logo_url"));
		payload.put("company_status", LegacyValues.toPhpString(row.get("status")));
		payload.put("has_active_branch", store.hasActiveBranch(companyId));
		return payload;
	}

	// ---------------- check_status.php ----------------

	/**
	 * {@code check_status.php} -- four outcomes, all of them <b>200</b>. It is
	 * a screen router, not a guard: it never fails, and the client decides what
	 * to show from the {@code screen} key.
	 *
	 * <p>The order matters: not-found, then company-inactive, then
	 * employee-deactivated, then active. An employee deactivated at an inactive
	 * company reports the company reason.
	 */
	public StatusScreen checkStatus(Map<String, Object> body, String locale) {
		required(body, "phone", "company_id");
		Map<String, Object> row = store.employeeStatus(
				LegacyValues.toPdoBindValue(body.get("phone")) == null
						? null : LegacyValues.toPhpString(body.get("phone")),
				LegacyValues.toPhpLong(body.get("company_id")));

		Map<String, Object> data = new LinkedHashMap<>();
		if (row == null) {
			data.put("screen", "enter_company_code");
			return status("status_not_found", data);
		}
		String companyStatus = LegacyValues.toPhpString(row.get("company_status"));
		if (!ACTIVE.equals(companyStatus)) {
			data.put("screen", "company_inactive");
			data.put("company_status", companyStatus);
			return status("status_company_inactive", data);
		}
		if (LegacyValues.toPhpLong(row.get("is_active")) == 0) {
			data.put("screen", "enter_company_code");
			data.put("message", messages.translate(locale, "account_not_linked_message", null));
			return status("status_deactivated", data);
		}
		data.put("screen", "home");
		data.put("role", LegacyValues.toPhpString(row.get("role")));
		return status("status_active", data);
	}

	/**
	 * One of {@code check_status.php}'s four 200 responses. The message key is
	 * part of the outcome rather than a controller constant, because which of
	 * the four fired is exactly what the endpoint communicates.
	 */
	public record StatusScreen(String messageKey, Map<String, Object> data) {
	}

	private static StatusScreen status(String messageKey, Map<String, Object> data) {
		return new StatusScreen(messageKey, data);
	}

	// ---------------- register_company.php ----------------

	/**
	 * {@code register_company.php} -- step one of two.
	 *
	 * <h2>The {@code ALTER TABLE} is not ported, and that is a divergence</h2>
	 * <p>PHP wraps its insert in an {@code ensureColumn()} helper that, when a
	 * column is missing, runs {@code ALTER TABLE companies ADD COLUMN ...} <b>at
	 * request time, from a public unauthenticated endpoint</b>, and only fails
	 * if the column is still absent afterwards.
	 *
	 * <p>All five columns it guards -- {@code country_code}, {@code first_name},
	 * {@code last_name}, {@code email}, {@code status}, {@code otp_verified},
	 * {@code profile_completed} -- <b>exist</b> in {@code hr-legacy@d113204}'s
	 * schema, so every gate takes its early return and the DDL never runs. The
	 * branch is unreachable against the frozen schema.
	 *
	 * <p>It is therefore not reproduced. Running schema migrations from an
	 * anonymous HTTP request is a line this repository's production standards
	 * do not cross, and reproducing an unreachable branch buys nothing. What
	 * <em>is</em> reproduced is the observable half: the columns are still
	 * required, and their absence would still be an error rather than a silent
	 * skip. Recorded as a deliberate divergence in D-135 rather than left
	 * implicit.
	 */
	public Map<String, Object> registerCompany(
			HttpServletRequest request, Map<String, Object> body, String locale) {
		String firstName = trimmed(body, "first_name");
		String lastName = trimmed(body, "last_name");
		// required() is called on a *rebuilt* array whose name fields are the
		// trimmed values, so "  " fails while the raw body would have passed.
		requireValue("first_name", firstName);
		requireValue("last_name", lastName);
		requireValue("phone", body == null ? null : body.get("phone"));
		requireValue("password", body == null ? null : body.get("password"));

		// trim((string) ($body[COUNTRY_CODE] ?? '+20')) -- `??` treats an
		// explicit null exactly like an absent key, so {"country_code": null}
		// registers with +20. Testing containsKey() instead, as an earlier
		// draft did, left it as "" and rejected a valid Egyptian phone with
		// invalid_phone_number.
		String countryCode = body == null || body.get("country_code") == null
				? DEFAULT_COUNTRY_CODE
				: trimmed(body, "country_code");
		String phone = LegacyPhoneNumbers.digitsOnly(
				LegacyValues.phpTrim(LegacyValues.toPhpString(body.get("phone"))));
		if (!phoneNumbers.isValidLocal(countryCode, phone)) {
			throw new LegacyApiException(400, "invalid_phone_number");
		}

		// fail(PHONE_ALREADY_REGISTERED) with no status -- the default 400.
		if (store.companyPhoneExistsExactly(phone)) {
			throw new LegacyApiException(400, "phone_already_registered");
		}

		long companyId = store.insertCompany(
				firstName, lastName, countryCode, phone,
				passwordEncoder.encode(LegacyValues.toPhpString(body.get("password"))),
				LegacyEmployeeName.normalizeOptionalEmail(body.get("email")));

		// The OTP is issued after the row exists, so a delivery failure leaves a
		// registered-but-unverified company behind. That is legacy's ordering.
		otp.issueAndSendWhatsApp(request, phone, LegacyOtpService.SMS_OTP_VERIFY, countryCode, 10, locale);

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("company", LegacyPublicRow.of(store.company(companyId)));
		return payload;
	}

	// ---------------- complete_company_registration.php ----------------

	/** The scalar {@code $_POST} inputs of {@code complete_company_registration.php}. */
	public record CompleteRegistrationInput(
			long companyId, String companyCode, String companyName, String firstName, String lastName,
			String mainBranchAddress, long companyTitleId, long companyActivityId, long companySizeId) {
	}

	/**
	 * The two {@code uploadFile()} calls, deferred.
	 *
	 * <p>They must not run until the scalar checks, the company lookup and both
	 * state gates have passed, because {@code uploadFile()} <b>writes to
	 * disk</b> and PHP reaches it only at that point in the file. Passing the
	 * stored URLs in as constructor arguments -- which is what an earlier draft
	 * did -- inverts that: Java evaluates arguments before the call, so a public
	 * request naming {@code company_id=0} would permanently store both files and
	 * then return 400. That is a divergence from the ported order and a cheap way
	 * for an unauthenticated caller to accumulate orphaned files, so the calls
	 * are passed as suppliers and invoked in PHP's position.
	 */
	public interface UploadedFiles {
		/** {@code uploadFile(LOGO, LOGOS)}; null when nothing was uploaded. */
		String logoUrl();

		/** {@code uploadFile('commercial_reg', COMMERCIAL)}; null when nothing was uploaded. */
		String commercialRegUrl();
	}

	/**
	 * {@code complete_company_registration.php} -- step two.
	 *
	 * <p><b>This endpoint is unauthenticated and hands back a company-admin
	 * token for a caller-supplied {@code company_id} (R-016).</b> Its only
	 * gates are that the row exists, {@code otp_verified = 1} and
	 * {@code profile_completed ≠ 1}. Ported in parity form because that is
	 * Phase 1's contract; the finding is recorded in the risk register, the
	 * threat model and the endpoint inventory so that shipping it is a visible
	 * decision.
	 *
	 * <p>The validation order is fully observable and preserved: the six
	 * required scalars first (each naming its own field), then the optional
	 * code's shape and uniqueness, then the company row, then the two state
	 * gates, then <b>the uploads</b>, and only then the three foreign keys.
	 *
	 * <p>Two consequences of that order, both legacy's. A request rejected by
	 * any gate <em>above</em> the uploads writes no file at all -- which is why
	 * {@link UploadedFiles} is a supplier rather than a value. And a request
	 * with a bad {@code company_title_id} is rejected <em>below</em> them, so it
	 * does store both files and leave them orphaned.
	 */
	public Map<String, Object> completeRegistration(
			CompleteRegistrationInput input, UploadedFiles uploads, String locale) {
		if (input.companyId() <= 0) {
			throw fieldRequired("company_id");
		}
		if (input.companyName().isEmpty()) {
			throw fieldRequired("company_name");
		}
		if (input.mainBranchAddress().isEmpty()) {
			throw fieldRequired("main_branch_address");
		}
		if (input.companyTitleId() <= 0) {
			throw fieldRequired("company_title_id");
		}
		if (input.companyActivityId() <= 0) {
			throw fieldRequired("company_activity_id");
		}
		if (input.companySizeId() <= 0) {
			throw fieldRequired("company_size_id");
		}

		if (!input.companyCode().isEmpty()) {
			if (!LegacyCompanyCode.isValid(input.companyCode())) {
				throw new LegacyApiException(400, "company_code_invalid");
			}
			if (store.codeIsTaken(input.companyCode(), input.companyId())) {
				throw new LegacyApiException(400, "company_code_taken");
			}
		}

		Map<String, Object> company = store.company(input.companyId());
		if (company == null) {
			throw new LegacyApiException(404, "not_found");
		}
		if (LegacyValues.toPhpLong(company.get("otp_verified")) != 1) {
			throw new LegacyApiException(403, "verify_otp_first");
		}
		if (LegacyValues.toPhpLong(company.get("profile_completed")) == 1) {
			throw new LegacyApiException(400, "nothing_to_update");
		}

		// Only now are the files written -- PHP's own position for these two
		// calls, after every gate above.
		String logoUrl = uploads.logoUrl();
		String commercialRegUrl = uploads.commercialRegUrl();
		if (logoUrl == null) {
			throw new LegacyApiException(400, "no_file_uploaded");
		}
		if (commercialRegUrl == null) {
			throw fieldRequired("commercial_reg");
		}

		// One shared fail(FIELD_REQUIRED, 400) with *no* field name for all
		// three foreign keys -- the client cannot tell which one was wrong.
		if (!store.companyTitleExists(input.companyTitleId())
				|| !store.companyActivityExists(input.companyActivityId())
				|| !store.companySizeExists(input.companySizeId())) {
			throw new LegacyApiException(400, "field_required");
		}

		List<String> assignments = new ArrayList<>();
		List<Object> binds = new ArrayList<>();
		assign(assignments, binds, "company_name", input.companyName());
		assign(assignments, binds, "company_code",
				input.companyCode().isEmpty() ? null : input.companyCode());
		// first_name and last_name are written only when non-empty, so step two
		// cannot blank the names step one collected.
		if (!input.firstName().isEmpty()) {
			assign(assignments, binds, "first_name", input.firstName());
		}
		if (!input.lastName().isEmpty()) {
			assign(assignments, binds, "last_name", input.lastName());
		}
		assign(assignments, binds, "main_branch_address", input.mainBranchAddress());
		assign(assignments, binds, "company_title_id", input.companyTitleId());
		assign(assignments, binds, "company_activity_id", input.companyActivityId());
		assign(assignments, binds, "company_size_id", input.companySizeId());
		assign(assignments, binds, "logo_url", logoUrl);
		assign(assignments, binds, "commercial_reg_url", commercialRegUrl);
		assign(assignments, binds, "profile_completed", 1);

		store.completeRegistration(assignments, binds, input.companyId());

		// The main branch is created only when the company has *no* branch at
		// all -- active or not -- so a company that already has one keeps it.
		if (!store.hasAnyBranch(input.companyId())) {
			store.insertMainBranch(input.companyId(), input.companyName(), input.mainBranchAddress());
		}

		Map<String, Object> updated = store.company(input.companyId());
		ensureOnboarding(input.companyId(), locale);

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("company", LegacyPublicRow.of(updated));
		return payload;
	}

	// ---------------- register_employee.php ----------------

	/**
	 * {@code register_employee.php} -- the <b>older</b> of the two join paths,
	 * kept alongside {@code join_company.php}.
	 *
	 * <p>Its "company code" is the company's <b>phone number</b>, matched
	 * exactly, not the public {@code company_code} column. It creates an
	 * employee with no name, no branch and <b>no {@code join_request_status}</b>
	 * -- so the column takes its {@code 'accepted'} default and the employee is
	 * immediately accepted, where {@code join_company.php} creates a
	 * {@code 'pending'} row. Two endpoints, two different meanings of joining,
	 * both live.
	 */
	public Map<String, Object> registerEmployee(Map<String, Object> body) {
		required(body, "phone", "password", "company_code");
		Map<String, Object> company = store.companyByPhoneExactly(
				LegacyValues.toPhpString(body.get("company_code")));
		if (company == null || !ACTIVE.equals(LegacyValues.toPhpString(company.get("status")))) {
			throw new LegacyApiException(404, "invalid_inactive_company_code");
		}
		long companyId = LegacyValues.toPhpLong(company.get("id"));

		String phone = LegacyValues.toPhpString(body.get("phone"));
		if (store.employeeExistsInCompanyExactly(phone, companyId)) {
			// fail() with no status -- the default 400.
			throw new LegacyApiException(400, "phone_registered_in_company");
		}

		long employeeId = store.insertEmployeeMinimal(companyId, phone,
				passwordEncoder.encode(LegacyValues.toPhpString(body.get("password"))));

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("company_id", companyId);
		payload.put("employee_id", employeeId);
		return payload;
	}

	// ---------------- join_company.php ----------------

	/** The employee row and the company it joined, for the controller to tokenise. */
	public record JoinResult(long employeeId, long companyId, Map<String, Object> employee) {
	}

	/**
	 * {@code join_company.php} -- the current join path.
	 *
	 * <p>Its checks are in an order that is worth stating because two of them
	 * are skipped for one caller: if the phone is <b>the company's own</b>
	 * ({@code phones_are_equivalent}), both global-uniqueness checks are
	 * bypassed, so a company owner can create an employee account for
	 * themselves at their own company even though that number is already
	 * registered. Everyone else is refused with 409.
	 *
	 * <p>The duplicate probe treats a {@code rejected} row as absent, so a
	 * rejected applicant may apply again.
	 *
	 * <p>Two notifications are written after the insert -- one to the employee,
	 * one to the company -- and neither is transactional with it (D-089).
	 */
	public JoinResult joinCompany(Map<String, Object> body, String locale) {
		required(body, "first_name", "phone", "password");

		String rawCountry = trimmed(body, "country_code");
		String countryCode = phoneNumbers.resolveCode(
				rawCountry.isEmpty() ? phoneNumbers.resolveCode("") : rawCountry);
		String phone = LegacyPhoneNumbers.digitsOnly(
				LegacyValues.phpTrim(LegacyValues.toPhpString(body.get("phone"))));
		if (!phoneNumbers.isValidLocal(countryCode, phone)) {
			throw new LegacyApiException(400, "invalid_phone_number");
		}

		String code = LegacyCompanyCode.normalize(body.get("company_code"));
		if (code.isEmpty()) {
			throw fieldRequired("company_code");
		}
		if (!LegacyCompanyCode.isValid(code)) {
			throw new LegacyApiException(400, "company_code_invalid");
		}

		Map<String, Object> company = store.findByPublicCode(code);
		if (company == null || !ACTIVE.equals(LegacyValues.toPhpString(company.get("status")))) {
			throw new LegacyApiException(404, "invalid_inactive_company_code");
		}
		long companyId = LegacyValues.toPhpLong(company.get("id"));

		long branchId = store.firstActiveBranchId(companyId);
		if (branchId <= 0) {
			throw new LegacyApiException(400, "company_no_active_branch_for_join");
		}

		if (store.joinRequestAlreadyExists(phone, companyId)) {
			throw new LegacyApiException(400, "phone_registered_in_company");
		}

		boolean isOwnerPhone = LegacyPhoneNumbers.areEquivalent(
				LegacyValues.toPhpString(company.get("phone")), phone);
		if (!isOwnerPhone && employeeStore.phoneExistsGlobally(phone, null)) {
			throw new LegacyApiException(409, "phone_already_used_try_login");
		}
		if (!isOwnerPhone && store.companyPhoneExistsGlobally(phone, companyId)) {
			throw new LegacyApiException(409, "phone_already_used_try_login");
		}

		LegacyEmployeeName.Name name = LegacyEmployeeName.fromBody(body, phone);
		long employeeId;
		try {
			employeeId = store.insertJoinRequestEmployee(
					companyId, branchId, name.firstName(), name.lastName(), phone,
					passwordEncoder.encode(LegacyValues.toPhpString(body.get("password"))));
		} catch (RuntimeException ex) {
			// PHP catches Throwable here, tests db_is_duplicate_entry(), and picks
			// the message by whether the driver's text mentions "phone". This is
			// not belt-and-braces around the checks above: `employees.phone` is
			// UNIQUE *globally*, while joinRequestAlreadyExists() only looks
			// inside one company and deliberately ignores rejected rows -- so a
			// rejected applicant re-applying passes every check and lands here.
			if (!isDuplicateEntry(ex)) {
				throw ex;
			}
			throw new LegacyApiException(409, duplicateMessage(ex));
		}

		String display = LegacyValues.phpTrim(name.firstName() + " " + name.lastName());
		if (display.isEmpty()) {
			display = phone;
		}
		String companyName = LegacyValues.phpTrim(LegacyValues.toPhpString(company.get("company_name")));
		if (companyName.isEmpty()) {
			companyName = code;
		}

		notifications.toEmployee(companyId, employeeId, null, "join_request_submitted",
				messages.translate(locale, "notif_join_request_submitted_title", null),
				messages.translate(locale, "notif_join_request_submitted_body",
						Map.of("company", companyName)),
				"employee", employeeId);
		notifications.toCompany(companyId, employeeId, "join_request_submitted",
				messages.translate(locale, "notif_join_request_received_title", null),
				messages.translate(locale, "notif_join_request_received_body",
						Map.of("employee", display)),
				"employee", employeeId);

		return new JoinResult(employeeId, companyId, LegacyPublicRow.of(store.employee(employeeId)));
	}

	// ---------------- login_company.php / login_desktop.php ----------------

	/** What a company login decided, so the controller can mint the right response. */
	public sealed interface CompanyLoginResult {
		/** {@code ok(VERIFY_OTP_FIRST, [company, otp_required => true])} -- 200, no token. */
		record VerifyOtpFirst(Map<String, Object> company) implements CompanyLoginResult {
		}

		/** {@code login_desktop.php} only: profile incomplete, so company and <b>no token</b>. */
		record ProfileIncomplete(Map<String, Object> company) implements CompanyLoginResult {
		}

		/** The normal outcome: company plus a company-admin token. */
		record Authenticated(Map<String, Object> company, long companyId) implements CompanyLoginResult {
		}
	}

	/**
	 * The company branch shared by {@code login_company.php} and
	 * {@code login_desktop.php}'s {@code login_as=company} path.
	 *
	 * <p>The two are <b>almost</b> the same and differ in three places, all
	 * preserved by the {@code desktop} flag:
	 * <ul>
	 * <li>an unknown phone is {@code invalid_phone_password} 401 on mobile and
	 *     {@code company_not_registered} 401 on desktop -- desktop tells the
	 *     caller the phone is unknown, mobile does not;</li>
	 * <li>a wrong password is {@code invalid_phone_password} on mobile and
	 *     {@code incorrect_password} on desktop;</li>
	 * <li>an incomplete profile is <b>403 {@code complete_company_profile_first}</b>
	 *     on mobile and a <b>200 with the company and no token</b> on desktop.</li>
	 * </ul>
	 *
	 * <p>Everything after that is identical, including the part that reads
	 * oddly: a {@code pending} company <em>is</em> allowed to log in, and only a
	 * status that is neither pending nor active reaches
	 * {@code company_pending_admin}.
	 */
	public CompanyLoginResult companyLogin(
			HttpServletRequest request, Map<String, Object> body, boolean desktop, String locale) {
		Map<String, Object> company = store.companyByPhoneForLogin(
				LegacyValues.toPdoBindValue(body.get("phone")));
		if (company == null) {
			throw new LegacyApiException(401,
					desktop ? "company_not_registered" : "invalid_phone_password");
		}
		if (!passwordMatches(LegacyValues.toPhpString(body.get("password")),
				(String) company.get("password_hash"))) {
			throw new LegacyApiException(401,
					desktop ? "incorrect_password" : "invalid_phone_password");
		}

		if (LegacyValues.toPhpLong(company.get("otp_verified")) == 0) {
			String phone = LegacyOtpService.normalizePhone(body.get("phone"));
			if (!otp.hasRecentForPhone(phone, 60)) {
				String countryCode = LegacyValues.phpTrim(
						LegacyValues.toPhpString(company.get("country_code")));
				if (LegacyValues.isPhpEmpty(countryCode)) {
					countryCode = DEFAULT_COUNTRY_CODE;
				}
				otp.issueAndSendWhatsApp(
						request, phone, LegacyOtpService.SMS_OTP_VERIFY, countryCode, 10, locale);
			}
			return new CompanyLoginResult.VerifyOtpFirst(LegacyPublicRow.of(company));
		}

		if (LegacyValues.toPhpLong(company.get("profile_completed")) != 1) {
			if (desktop) {
				return new CompanyLoginResult.ProfileIncomplete(LegacyPublicRow.of(company));
			}
			throw new LegacyApiException(403, "complete_company_profile_first");
		}

		String status = LegacyValues.toPhpString(company.get("status"));
		if (REJECTED.equals(status)) {
			throw new LegacyApiException(403, "company_rejected", null,
					Map.of("reason", LegacyValues.toPhpString(company.get("rejection_reason"))));
		}
		if (SUSPENDED.equals(status)) {
			throw new LegacyApiException(403, "company_suspended");
		}
		if (!PENDING.equals(status) && !ACTIVE.equals(status)) {
			throw new LegacyApiException(403, "company_pending_admin");
		}

		long companyId = LegacyValues.toPhpLong(company.get("id"));
		if (desktop) {
			// login_company.php does NOT do this; login_desktop.php does.
			ensureOnboarding(companyId, locale);
		}
		return new CompanyLoginResult.Authenticated(LegacyPublicRow.of(company), companyId);
	}

	/**
	 * {@code login_desktop.php}'s {@code login_as=hr} branch.
	 *
	 * <p>It is <b>HR only</b> and says so in SQL: the query filters
	 * {@code role = 'hr'} and {@code is_active = 1}, so a company admin or a
	 * plain employee with the same phone is {@code user_not_found} 401 here
	 * even though {@code login_employee.php} would let them in. It orders
	 * {@code e.id ASC} -- oldest first -- where every other login path in the
	 * system orders newest first, and takes the first row whose password
	 * verifies.
	 *
	 * <p>{@code requireCompanyActive()} runs <b>after</b> the password check,
	 * so a correct password at a suspended company is told the company is
	 * inactive while a wrong one is told the password is wrong.
	 *
	 * <p>The employee is then re-read <em>with</em> the permission join, so
	 * {@code employee_row_attach_hr_permissions()} takes its row branch. The
	 * token is issued between the two reads.
	 */
	public Map<String, Object> desktopHrLogin(Map<String, Object> body, String locale) {
		List<Map<String, Object>> rows = store.activeHrByPhoneOldestFirst(
				LegacyValues.toPdoBindValue(body.get("phone")));
		if (rows.isEmpty()) {
			throw new LegacyApiException(401, "user_not_found");
		}

		String password = LegacyValues.toPhpString(body.get("password"));
		Map<String, Object> employee = null;
		for (Map<String, Object> candidate : rows) {
			if (passwordMatches(password, (String) candidate.get("password_hash"))) {
				employee = candidate;
				break;
			}
		}
		if (employee == null) {
			throw new LegacyApiException(401, "incorrect_password");
		}

		long companyId = LegacyValues.toPhpLong(employee.get("company_id"));
		String companyStatus = LegacyValues.toPhpString(employee.get("company_status"));
		if (!ACTIVE.equals(companyStatus)) {
			// requireCompanyActive()
			throw new LegacyApiException(403, "company_account_not_active");
		}

		Map<String, Object> company = store.company(companyId);
		if (company == null) {
			throw new LegacyApiException(404, "not_found");
		}

		long employeeId = LegacyValues.toPhpLong(employee.get("id"));
		String token = sessionTokens.issue(
				employeeId, companyId, LegacyValues.toPhpString(employee.get("role")));

		Map<String, Object> withPermissions = store.employeeWithPermissions(employeeId);
		if (withPermissions != null) {
			permissionRows.attach(withPermissions);
		}

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("token", token);
		payload.put("company", LegacyPublicRow.of(company));
		payload.put("employee", withPermissions == null ? null : LegacyPublicRow.of(withPermissions));
		return payload;
	}

	// ---------------- shared ----------------

	private void ensureOnboarding(long companyId, String locale) {
		notifications.ensureCompanyOnboarding(companyId,
				messages.translate(locale, "notif_company_welcome_title", null),
				messages.translate(locale, "notif_company_welcome_body", null),
				messages.translate(locale, "notif_company_pending_title", null),
				messages.translate(locale, "notif_company_pending_body", null));
	}

	/**
	 * {@code db_is_duplicate_entry()}: MySQL error <b>1062</b> specifically,
	 * never every SQLSTATE 23000 -- which would also catch the NOT NULL and
	 * foreign-key violations this module can genuinely produce.
	 */
	private static boolean isDuplicateEntry(Throwable ex) {
		return causeText(ex).contains("1062") || causeText(ex).contains("Duplicate entry");
	}

	/** {@code str_contains(strtolower($e->getMessage()), 'phone')}. */
	private static String duplicateMessage(Throwable ex) {
		return causeText(ex).toLowerCase(java.util.Locale.ROOT).contains("phone")
				? "phone_already_used_try_login"
				: "phone_registered_in_company";
	}

	private static String causeText(Throwable ex) {
		StringBuilder text = new StringBuilder();
		for (Throwable current = ex; current != null; current = current.getCause()) {
			if (current.getMessage() != null) {
				text.append(current.getMessage()).append('\n');
			}
			if (current.getCause() == current) {
				break;
			}
		}
		return text.toString();
	}

	private boolean passwordMatches(String password, String hash) {
		try {
			return hash != null && !hash.isEmpty() && passwordEncoder.matches(password, hash);
		} catch (RuntimeException ex) {
			return false;
		}
	}

	private static void assign(List<String> assignments, List<Object> binds, String column, Object value) {
		assignments.add(column + " = ?");
		binds.add(value);
	}

	private static String trimmed(Map<String, Object> body, String key) {
		Object value = body == null ? null : body.get(key);
		return LegacyValues.phpTrim(value == null ? "" : LegacyValues.toPhpString(value));
	}

	private static LegacyApiException fieldRequired(String field) {
		return new LegacyApiException(400, "field_required", null, Map.of("field", field));
	}

	private static void requireValue(String field, Object value) {
		if (value == null || "".equals(value)) {
			throw fieldRequired(field);
		}
	}

	private static void required(Map<String, Object> body, String... keys) {
		for (String key : keys) {
			requireValue(key, body == null ? null : body.get(key));
		}
	}
}
