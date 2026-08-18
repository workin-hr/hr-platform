package com.workin.legacy.auth;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/legacy/auth/login_employee} (punch-list item #9).
 *
 * <p>A new path, not {@code /api/auth/login}: that path is
 * {@code LoginService} against the PostgreSQL {@code identities}/
 * {@code tenant_memberships} model, a different table, different token
 * claims, different failure catalogue. Scoped under
 * {@code /api/legacy/**} so {@code SecurityConfig}'s
 * {@code legacySecurityFilterChain} matcher stays clean.
 *
 * <p>Deliberately thin (repository owner's direction, 2026-08-18, D-049
 * Follow-up (c)): the login use case -- phone lookup, credential
 * resolution, refresh-token issuance, the {@code token_version} bump,
 * and JWT issuance -- is one transactional application operation, so it
 * lives in {@link LegacyLoginService} and owns its own transaction
 * there. This controller only parses the request and maps {@link
 * LegacyLoginOutcome}'s status/message-key contract onto HTTP; it does
 * not re-derive it.
 *
 * <p>No {@code @PublicUseCase}/{@code @RequiresPermission} declaration:
 * {@code AuthorizationPolicyArchTest}'s F-23 guard scans only
 * {@code com.workin.backend}, and the interceptor those annotations
 * feed ({@code AuthorizationPolicyInterceptor}) is itself Postgres-only
 * and not registered under {@code phase1-mysql} -- {@code hr_permissions}
 * authorization mapping does not exist for the legacy contract yet
 * (punch-list item #11). Annotating here would be decorative.
 */
@RestController
public class LegacyLoginController {

	private final LegacyLoginService legacyLoginService;

	public LegacyLoginController(LegacyLoginService legacyLoginService) {
		this.legacyLoginService = legacyLoginService;
	}

	@PostMapping("/api/legacy/auth/login_employee")
	public LegacyAuthResponse login(@Valid @RequestBody LegacyLoginRequest request) {
		return legacyLoginService.login(request);
	}

}
