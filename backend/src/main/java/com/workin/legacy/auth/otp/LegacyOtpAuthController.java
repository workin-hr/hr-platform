package com.workin.legacy.auth.otp;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.legacy.LegacyJsonBody;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Wave 13.1a: the four unauthenticated OTP routes of {@code apis/api/auth/}.
 *
 * <p><b>All four are public and none is authenticated</b> -- that is legacy's
 * design and the reason this is the wave D-128 deliberately scheduled last.
 * Two of them (`resend_otp`, `forgot_password`) send a WhatsApp message to a
 * caller-chosen number, and the only thing standing between them and abuse is
 * {@link LegacyOtpRateLimit}, whose per-IP cap is currently a platform-wide cap
 * (R-014).
 *
 * <p>None of them returns the OTP. See {@link LegacyOtpAuthService} for why the
 * {@code AppConfig::DEBUG} branch that would is not ported.
 */
@RestController
public class LegacyOtpAuthController {

	private final LegacyOtpAuthService service;
	private final LegacyMessages messages;

	public LegacyOtpAuthController(LegacyOtpAuthService service, LegacyMessages messages) {
		this.service = service;
		this.messages = messages;
	}

	@RequestMapping("/apis/api/auth/verify_otp.php")
	public LegacyApiResponse verifyOtp(HttpServletRequest request) {
		requireMethod(request, "POST");
		service.verifyOtp(LegacyJsonBody.read(request));
		return LegacyApiResponse.ok(message(request, "otp_verified_successfully"), null);
	}

	/** {@code ok(OTP_SENT, [])} -- an empty JSON array, not a missing key. */
	@RequestMapping("/apis/api/auth/resend_otp.php")
	public LegacyApiResponse resendOtp(HttpServletRequest request) {
		requireMethod(request, "POST");
		service.resendOtp(request, LegacyJsonBody.read(request), messages.resolveLocale(request));
		return LegacyApiResponse.ok(
				message(request, "otp_sent"), LegacyOtpAuthService.emptyOtpData());
	}

	@RequestMapping("/apis/api/auth/forgot_password.php")
	public LegacyApiResponse forgotPassword(HttpServletRequest request) {
		requireMethod(request, "POST");
		service.forgotPassword(request, LegacyJsonBody.read(request), messages.resolveLocale(request));
		return LegacyApiResponse.ok(
				message(request, "otp_sent_password_reset"), LegacyOtpAuthService.emptyOtpData());
	}

	@RequestMapping("/apis/api/auth/reset_password.php")
	public LegacyApiResponse resetPassword(HttpServletRequest request) {
		requireMethod(request, "POST");
		service.resetPassword(LegacyJsonBody.read(request));
		return LegacyApiResponse.ok(message(request, "password_reset_successful"), null);
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
