package com.workin.legacy.auth.otp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyLoginCandidate;
import com.workin.legacy.auth.LegacyPhoneAuthResolver;
import com.workin.legacy.auth.LegacyRefreshTokenService;
import com.workin.legacy.wire.LegacyApiException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The four OTP endpoints of {@code apis/api/auth/}: {@code verify_otp},
 * {@code resend_otp}, {@code forgot_password} and {@code reset_password}.
 *
 * <h2>The OTP code never reaches the response</h2>
 * <p>Legacy returns it when {@code AppConfig::DEBUG} is truthy --
 * {@code ok(..., AppConfig::DEBUG ? [OTP => $code] : [])}. Production's
 * {@code DEBUG} was confirmed {@code true} on 2026-08-04 and <b>changed to
 * {@code false} on 2026-08-05</b> by the repository owner, closing what the
 * threat model records as a complete authentication bypass. PMR-05 and
 * {@code hr-legacy#4} make "no {@code DEBUG}-gated secret exception" a
 * mandatory requirement of this rewrite.
 *
 * <p>So the false branch is the only branch here: the response data is the
 * empty array PHP produces with {@code DEBUG} off. This is parity with the
 * running system and an already-made decision, not a new one.
 */
@Service
public class LegacyOtpAuthService {

	private static final String COMPANY = "company";
	private static final String EMPLOYEE = "employee";
	private static final String DEFAULT_COUNTRY_CODE = "+20";

	private final LegacyOtpService otp;
	private final LegacyOtpAuthStore store;
	private final PasswordEncoder passwordEncoder;
	private final LegacyRefreshTokenService refreshTokens;

	public LegacyOtpAuthService(
			LegacyOtpService otp, LegacyOtpAuthStore store, PasswordEncoder passwordEncoder,
			LegacyRefreshTokenService refreshTokens) {
		this.otp = otp;
		this.store = store;
		this.passwordEncoder = passwordEncoder;
		this.refreshTokens = refreshTokens;
	}

	/**
	 * {@code verify_otp.php}.
	 *
	 * <p>Two flags decide what happens after a valid code: the auth
	 * {@code type} and whether {@code purpose} is exactly
	 * {@code "password_reset"}. A company verification marks
	 * {@code otp_verified}; anything that is not a password reset then
	 * <b>consumes</b> the code. A password reset deliberately leaves it active,
	 * because {@code reset_password.php} verifies the same code again.
	 *
	 * <p>{@code type} is compared with {@code ===} against the string
	 * {@code "company"}, so a JSON boolean or number never matches and simply
	 * skips the company update -- no error.
	 */
	public void verifyOtp(Map<String, Object> body) {
		required(body, "phone", "otp", "type");
		String phone = LegacyOtpService.normalizePhone(body.get("phone"));
		Object authType = body.get("type");
		String purpose = body.get("purpose") == null
				? "" : LegacyValues.phpTrim(LegacyValues.toPhpString(body.get("purpose")));
		boolean isPasswordReset = "password_reset".equals(purpose);

		if (otp.verifyLatestForPhone(phone, body.get("otp")) == null) {
			throw new LegacyApiException(400, "invalid_expired_otp");
		}

		if (COMPANY.equals(authType) && !isPasswordReset) {
			store.markCompanyOtpVerified(phone);
		}
		if (!isPasswordReset) {
			otp.clearForPhone(phone);
		}
	}

	/**
	 * {@code resend_otp.php}.
	 *
	 * <p>Its own recency guard answers {@code please_wait_before_resending}
	 * with the <b>default 400</b> -- {@code fail()} is called with no status
	 * argument, unlike every other cooldown in the system, which is 429. The
	 * rate limiter inside {@code otp_issue_and_send_whatsapp()} then checks the
	 * same 60-second window again and would answer 429; in practice the 400
	 * always wins because it is checked first.
	 *
	 * <p>It does not verify that the phone belongs to anybody. Any number can
	 * be sent an OTP through this route, once a minute.
	 */
	public void resendOtp(HttpServletRequest request, Map<String, Object> body, String locale) {
		required(body, "phone");
		String phone = LegacyOtpService.normalizePhone(body.get("phone"));
		if (otp.hasRecentForPhone(phone, 60)) {
			throw new LegacyApiException(400, "please_wait_before_resending");
		}
		otp.issueAndSendWhatsApp(request, phone, LegacyOtpService.SMS_OTP_RESEND, null, 10, locale);
	}

	/**
	 * {@code forgot_password.php}.
	 *
	 * <p>An unknown {@code type} is {@code invalid_input} 400; a known type
	 * with no matching account is {@code phone_not_found} 404 -- so the
	 * endpoint tells an unauthenticated caller whether a phone is registered.
	 * That is legacy's contract and the clients depend on the 404.
	 *
	 * <p>When an account is found, the OTP is keyed on the <b>stored</b> phone
	 * rather than the submitted one, so the code that
	 * {@code reset_password.php} later looks up under the stored spelling is
	 * the same row.
	 */
	public void forgotPassword(HttpServletRequest request, Map<String, Object> body, String locale) {
		required(body, "phone", "type");
		String phone = LegacyOtpService.normalizePhone(body.get("phone"));
		Object authType = body.get("type");
		String countryCode = null;
		boolean found = false;

		if (COMPANY.equals(authType)) {
			Map<String, Object> company = store.findCompanyByPhone(phone);
			if (company != null) {
				found = true;
				phone = LegacyOtpService.normalizePhone(company.get("phone"));
				countryCode = LegacyValues.toPhpString(company.get("country_code"));
			}
		} else if (EMPLOYEE.equals(authType)) {
			long companyId = body.get("company_id") == null
					? 0L : LegacyValues.toPhpLong(body.get("company_id"));
			if (companyId > 0) {
				Map<String, Object> employee = store.findEmployeeByPhoneInCompany(phone, companyId);
				if (employee != null) {
					found = true;
					phone = LegacyOtpService.normalizePhone(employee.get("phone"));
					countryCode = LegacyValues.toPhpString(employee.get("country_code"));
				}
			} else {
				// resolve_single_employee_auth_by_phone() throws on every
				// rejection, so reaching the next line means an account exists.
				LegacyLoginCandidate candidate = LegacyPhoneAuthResolver.resolve(
						store.employeeAuthCandidatesByPhone(phone));
				found = true;
				Map<String, Object> contact = store.employeeContact(candidate.employeeId());
				if (contact != null) {
					Object stored = contact.get("phone");
					phone = LegacyOtpService.normalizePhone(stored == null ? phone : stored);
					// PHP keeps the row's country code rather than re-deriving it.
					countryCode = LegacyValues.toPhpString(contact.get("country_code"));
				}
			}
		} else {
			throw new LegacyApiException(400, "invalid_input");
		}

		if (!found) {
			throw new LegacyApiException(404, "phone_not_found");
		}

		// trim((string) ($found[COUNTRY_CODE] ?? '')) ?: '+20' -- the ?: makes
		// "0" fall back too, which the ?? alone would not.
		String resolved = LegacyValues.phpTrim(countryCode == null ? "" : countryCode);
		if (LegacyValues.isPhpEmpty(resolved)) {
			resolved = DEFAULT_COUNTRY_CODE;
		}
		otp.issueAndSendWhatsApp(
				request, phone, LegacyOtpService.SMS_OTP_PASSWORD_RESET, resolved, 10, locale);
	}

	/**
	 * {@code reset_password.php}.
	 *
	 * <p>The OTP is verified <b>before</b> the type is examined, so an invalid
	 * code is {@code invalid_expired_otp} even for a nonsense type. There is no
	 * minimum length on the new password here -- unlike
	 * {@code profile/change_password.php}'s six characters -- so a one-character
	 * password is accepted through this route.
	 *
	 * <p>The company branch updates <b>every</b> company whose phone matches a
	 * variant, not one row. Two companies sharing a number both have their
	 * password replaced.
	 */
	public void resetPassword(Map<String, Object> body) {
		required(body, "phone", "password", "otp", "type");
		String phone = LegacyOtpService.normalizePhone(body.get("phone"));
		Object authType = body.get("type");

		if (otp.verifyLatestForPhone(phone, body.get("otp")) == null) {
			throw new LegacyApiException(400, "invalid_expired_otp");
		}

		String hash = passwordEncoder.encode(LegacyValues.toPhpString(body.get("password")));

		if (COMPANY.equals(authType)) {
			store.updateCompanyPasswordByPhone(phone, hash);
		} else if (EMPLOYEE.equals(authType)) {
			long companyId = body.get("company_id") == null
					? 0L : LegacyValues.toPhpLong(body.get("company_id"));
			if (companyId <= 0) {
				LegacyLoginCandidate candidate = LegacyPhoneAuthResolver.resolve(
						store.employeeAuthCandidatesByPhone(phone));
				companyId = candidate.companyId();
				Map<String, Object> contact = store.employeeContact(candidate.employeeId());
				Object stored = contact == null ? null : contact.get("phone");
				phone = LegacyOtpService.normalizePhone(stored == null ? phone : stored);
			}
			// ADR-0005: "Logout and password change/reset revoke the relevant
			// session(s) -- closing the gap where hr-legacy password resets
			// never invalidate existing sessions." Legacy has no refresh tokens
			// at all, so this is not a parity divergence but the recorded
			// token-model exception (D-042) applied consistently. It is a no-op
			// today because no production route issues a legacy refresh token
			// yet -- the only issuer, LegacyLoginService, is reached solely by a
			// test-only controller -- and it is wired now so that the day that
			// changes, the reset already revokes.
			List<Long> affected = store.employeeIdsByPhoneInCompany(phone, companyId);
			store.updateEmployeePasswordByPhone(phone, companyId, hash);
			for (Long employeeId : affected) {
				refreshTokens.revokeAllForEmployee(employeeId);
			}
		} else {
			throw new LegacyApiException(400, "invalid_input");
		}

		otp.clearForPhone(phone);
	}

	/**
	 * {@code ok(LangKey::OTP_SENT, AppConfig::DEBUG ? [OTP => $code] : [])}.
	 *
	 * <p>PHP's empty array serialises as JSON {@code []}, not {@code {}}, so
	 * the response carries {@code "data": []}. That is what clients see today
	 * with {@code DEBUG} off, and reproducing the shape matters as much as
	 * withholding the code.
	 */
	public static Object emptyOtpData() {
		return java.util.List.of();
	}

	/** A {@code LinkedHashMap} so the caller controls key order (D-074). */
	static Map<String, Object> ordered() {
		return new LinkedHashMap<>();
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
