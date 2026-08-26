package com.workin.legacy.authorization;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.workin.backend.i18n.ApiException;
import com.workin.backend.i18n.MessageKeys;
import com.workin.backend.security.AuthenticatedPrincipal;

/**
 * Legacy's counterpart to {@code hr-legacy/apis/helpers/hr_permissions.php}'s
 * {@code require_hr_permission()} (punch-list item #11, D-044): a small,
 * explicit, per-call-site check, not a declarative annotation +
 * interceptor. That shape belongs to
 * {@code com.workin.backend.authorization}'s {@code AuthorizationPolicyInterceptor},
 * which is inseparable from the PostgreSQL redesign and already
 * {@code @Profile("!phase1-mysql")}-excluded (ADR-0013 §9). A legacy
 * controller method calls {@link #require} explicitly, the same way a
 * legacy PHP endpoint calls {@code require_hr_permission($auth, ...)}
 * explicitly -- opt-in per endpoint, not structural. D-044 requires
 * Phase 1 to reproduce that opt-in pattern's real gap
 * ({@code hr-legacy#8}: only ~21 of 150+ endpoints actually check it),
 * not close it, so this class enforces nothing on its own; it is only
 * ever as protective as the call sites that use it.
 *
 * <h2>Company-type bypass</h2>
 * <p>{@code hr_session_has_permission()} (({@code hr_permissions.php:143-153}):
 * a {@code type=company} session is always allowed, unconditionally, no
 * {@code hr_permissions} lookup at all. Checked first and returns before any
 * lookup: {@link AuthenticatedPrincipal#identityId()} for a non-employee
 * token carries the <em>company</em> id, not an employee id (see {@code
 * LegacyPhpJwtAuthenticationFilter#setPhpAuthentication}), so the lookup
 * below must never be handed one -- passing a company id to {@code
 * findByEmployeeId} either denies a company session PHP would allow, or,
 * where a company id numerically collides with an unrelated employee id,
 * grants that employee's unrelated permission flags. This was unreachable
 * before company-type legacy tokens existed in Phase 1 (D-042); it is
 * reachable now that {@code LegacyLoginPhpController} issues them.
 *
 * <h2>Trust boundary</h2>
 * <p>The employee id checked is always {@link AuthenticatedPrincipal#identityId()}
 * -- the identity the JWT's signature actually vouches for, the same
 * ground-truth value {@code LegacyTenantContextService#validate}
 * treats as authoritative -- never a request-supplied id. A missing or
 * non-legacy-shaped principal, or an employee with no
 * {@code hr_permissions} row at all, both deny by default, matching
 * {@code hr_permissions_for_employee()}'s own default-map-of-zeros
 * behaviour on a missing row exactly.
 */
@Service
public class LegacyHrPermissionEnforcer {

	private final LegacyHrPermissionsRepository legacyHrPermissionsRepository;

	public LegacyHrPermissionEnforcer(LegacyHrPermissionsRepository legacyHrPermissionsRepository) {
		this.legacyHrPermissionsRepository = legacyHrPermissionsRepository;
	}

	/**
	 * @throws ApiException 403 ({@link MessageKeys#ERROR_FORBIDDEN}) if
	 *         the authenticated employee lacks {@code key}, has no
	 *         {@code hr_permissions} row, or there is no authenticated
	 *         legacy principal at all
	 */
	public void require(LegacyHrPermissionKey key) {
		if (!hasPermission(key)) {
			throw new ApiException(HttpStatus.FORBIDDEN, MessageKeys.ERROR_FORBIDDEN);
		}
	}

	/**
	 * The same check without the {@code ApiException}, for callers that render a
	 * different error contract. {@code employees/update.php} is one: it fails
	 * with legacy's own {@code forbidden} key in the PHP envelope (D-074), while
	 * {@link #require} raises the platform {@code error.forbidden} the merged
	 * {@code /api/legacy/**} modules answer with.
	 */
	public boolean has(LegacyHrPermissionKey key) {
		return hasPermission(key);
	}

	private boolean hasPermission(LegacyHrPermissionKey key) {
		if (!(SecurityContextHolder.getContext().getAuthentication() != null
				&& SecurityContextHolder.getContext().getAuthentication().getPrincipal()
						instanceof AuthenticatedPrincipal principal)) {
			return false;
		}
		if ("company".equals(principal.legacyAuthType())) {
			return true;
		}
		return legacyHrPermissionsRepository.findByEmployeeId(principal.identityId())
				.map(row -> row.has(key))
				.orElse(false);
	}

}
