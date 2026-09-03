package com.workin.backend.platformadmin;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.workin.backend.authorization.PublicUseCase;
import com.workin.backend.platformadmin.mfa.PlatformAdminMfaService;
import com.workin.backend.i18n.ApiException;
import com.workin.backend.i18n.MessageKeys;

/**
 * No register endpoint here, deliberately -- see
 * {@link PlatformAdminBootstrap}.
 */
@RestController
@RequestMapping("/api/platform-admin")
public class PlatformAdminAuthController {

	private final PlatformAdminLoginService platformAdminLoginService;
	private final PlatformAdminJwtService platformAdminJwtService;
	private final PlatformAdminSessionService platformAdminSessionService;
	private final PlatformAdminMfaService mfaService;
	private final PlatformAdminLoginThrottle throttle;

	public PlatformAdminAuthController(
			PlatformAdminLoginService platformAdminLoginService,
			PlatformAdminJwtService platformAdminJwtService,
			PlatformAdminSessionService platformAdminSessionService,
			PlatformAdminMfaService mfaService,
			PlatformAdminLoginThrottle throttle) {
		this.platformAdminLoginService = platformAdminLoginService;
		this.platformAdminJwtService = platformAdminJwtService;
		this.platformAdminSessionService = platformAdminSessionService;
		this.mfaService = mfaService;
		this.throttle = throttle;
	}

	/**
	 * ADR-0015 prerequisite 8. Without a second factor here, this endpoint is a
	 * door around every control on the JTE surface: a stolen password mints a
	 * bearer token that the {@code /api/platform-admin/**} chain accepts for the
	 * same privileged operations the UI gates behind TOTP. Requiring TOTP on the
	 * UI while leaving this open does not produce MFA; it produces the
	 * appearance of it.
	 *
	 * <p>The contract is one request, not a challenge exchange: the code travels
	 * with the credentials. A challenge token would need its own issuance,
	 * expiry, single-use and replay handling -- a second credential lifecycle
	 * invented to avoid adding one field.
	 *
	 * <p>An administrator with no bound factor is refused outright rather than
	 * given a token. Enrolment is the web surface's ceremony (D-152), and
	 * handing out a bearer token to an account that cannot yet be
	 * second-factored would reopen exactly this hole for the accounts least
	 * protected against it.
	 *
	 * <p>No client outside this repository calls this endpoint, so D-111's
	 * zero-client-change rule does not constrain the shape change.
	 */
	@PublicUseCase(reason = "credential presentation for the platform domain -- authentication happens inside")
	@PostMapping("/login")
	public PlatformAdminAuthResponse login(@Valid @RequestBody PlatformAdminLoginRequest request) {
		PlatformAdmin platformAdmin = platformAdminLoginService.login(request);
		requireSecondFactor(platformAdmin, request.code());
		PlatformAdminSessionService.IssuedRefreshToken session = platformAdminSessionService
				.issue(platformAdmin.getId());
		String accessToken = platformAdminJwtService.issueAccessToken(
				platformAdmin.getId(), session.familyId().toString(), session.familyEndsAt());
		return new PlatformAdminAuthResponse(accessToken, session.rawToken(), platformAdmin.getId());
	}

	/**
	 * The second factor, enforced after the password check so the two failures
	 * are indistinguishable to a caller probing for valid phone numbers: both
	 * answer the same 401.
	 *
	 * <p>The TOTP attempt spends throttle budget under its own namespace. A
	 * six-digit code without throttling is a feasible online search, and the
	 * password step's budget must not be what protects it -- an attacker with a
	 * valid password would otherwise face no limit here at all.
	 */
	private void requireSecondFactor(PlatformAdmin platformAdmin, String code) {
		long id = platformAdmin.getId();
		if (!mfaService.isBound(id)) {
			throw new ApiException(HttpStatus.FORBIDDEN, MessageKeys.AUTH_MFA_NOT_ENROLLED);
		}
		String budgetKey = "totp-api:" + id;
		if (throttle.isExhausted(budgetKey) || code == null || !mfaService.verify(id, code)) {
			throttle.recordFailure(budgetKey);
			throw new ApiException(HttpStatus.UNAUTHORIZED, MessageKeys.AUTH_INVALID_CREDENTIALS);
		}
		throttle.clear(budgetKey);
	}

	@PublicUseCase(reason = "refresh-token possession is the credential; the access token may already be expired")
	@PostMapping("/refresh")
	public PlatformAdminAuthResponse refresh(@Valid @RequestBody PlatformAdminRefreshTokenRequest request) {
		PlatformAdminSessionService.RotatedSession session = platformAdminSessionService.rotate(request.refreshToken())
				.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, MessageKeys.AUTH_INVALID_REFRESH_TOKEN));
		String accessToken = platformAdminJwtService.issueAccessToken(
				session.platformAdminId(), session.familyId().toString(), session.familyEndsAt());
		return new PlatformAdminAuthResponse(accessToken, session.rawToken(), session.platformAdminId());
	}

	@PublicUseCase(reason = "idempotent revocation by refresh-token possession; never a validity oracle")
	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(@Valid @RequestBody PlatformAdminRefreshTokenRequest request) {
		platformAdminSessionService.logout(request.refreshToken());
	}

}
