package com.workin.legacy.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.i18n.ApiException;
import com.workin.backend.security.AuthenticatedPrincipal;
import com.workin.backend.tenancy.TenantScope;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.TenantFilterActivator;
import com.workin.legacy.companies.LegacyCompany;
import com.workin.legacy.companies.LegacyCompanyRepository;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.employees.LegacyEmployeeRepository;

/**
 * The legacy request-guard stack (D-045/D-049): P-2 (a reusable
 * request-scoped auth context, promoted out of the ad hoc
 * {@code SecurityContextHolder} reads {@link
 * com.workin.legacy.authorization.LegacyHrPermissionEnforcer} already
 * does inline), P-7 ({@code token_version} session-replacement) and P-8
 * (role authorization). P-9 (active-company) is {@link
 * #requireCompanyActive}, kept separate rather than folded into {@link
 * #requireAuth} because legacy itself calls it separately
 * ({@code requireCompanyActive($company_id)}, after {@code requireAuth()}
 * -- see every endpoint under {@code attendance_exception_types}).
 *
 * <p>Explicit call site, not an annotation/interceptor -- the same shape
 * D-044 chose for {@link com.workin.legacy.authorization.LegacyHrPermissionEnforcer}
 * (P-3), and the one legacy PHP itself uses
 * ({@code requireAuth([...]); requireCompanyActive($id); require_company_settings_access($auth);},
 * three explicit calls, not middleware).
 *
 * <h2>Why {@link #requireSessionValid} is deliberately tenant-blind (correction to an earlier draft)</h2>
 * <p>Legacy's own {@code requireEmployeeSessionValid()}
 * (`functions.php:505-521`) queries {@code SELECT token_version FROM
 * employees WHERE id = ?} -- <b>no {@code company_id} predicate at
 * all.</b> Session validity is a property of the authenticated
 * identity, independent of which company it claims. An earlier version
 * of this class queried through the tenant-filtered {@link
 * LegacyEmployeeRepository} directly, reasoning that a forged {@code
 * tenant_id} claim -- already rejected by {@code
 * LegacyTenantContextService}, leaving {@code TenantScope} unestablished
 * -- would make this lookup return empty too (bound to {@code
 * TenantFilter.NO_TENANT}) and so double as a tenant-forgery guard.
 * Building {@code LegacyExceptionTypeEndToEndTest}/{@code
 * LegacyTenantContextIsolationTest} proved that reasoning wrong in
 * practice: within one request, this lookup can observe the same
 * already-loaded {@code LegacyEmployee} instance {@code
 * LegacyTenantContextService#validate}'s own filter-disabled
 * pre-tenant lookup left in the persistence context, bypassing the
 * filter check entirely rather than re-querying under it. Tenant-claim
 * forgery was never this method's job in the first place -- it is
 * {@code LegacyTenantContextService}'s, already proven by {@code
 * LegacyTenantContextIsolationTest} independently of this class -- so
 * this now calls {@link TenantFilterActivator#deactivateForPreTenantLookup()}
 * explicitly and reads {@code token_version} the same tenant-blind way
 * PHP does, rather than depending on a side effect that turned out to
 * be unreliable.
 *
 * <h2>Why {@link #requireAuth} still checks {@link TenantScope#isEstablished()}
 * before returning a company id</h2>
 * <p>Making the session check tenant-blind (above) means it no longer
 * incidentally rejects a forged {@code tenant_id} claim -- and {@link
 * LegacyRequestContext#companyId()} feeds every write this module makes
 * ({@code LegacyExceptionTypeService.create/update/delete} all take it
 * as a plain, trusted {@code long}, since inserts and native
 * company-scoped clears are not protected by the read-side {@code
 * @Filter} the way a {@code SELECT} is). Returning {@code
 * principal.claimedCompanyId()} unchecked here would let a forged claim
 * that also happens to pass role and {@code hr_permissions} write under
 * a company the caller was never vouched for. So {@code requireAuth}
 * additionally requires {@link TenantScope#isEstablished()} -- true only
 * when {@code LegacyTenantContextService.validate} already re-derived
 * and cross-checked this exact claim during {@code SecurityConfig}'s
 * resolver -- and returns {@link TenantScope#current()}, the re-derived
 * value, never the bare claim. List/one pay the same cost for
 * consistency (one {@code requireAuth} call site for every method), and
 * a forged claim on a read now denies at the guard rather than falling
 * through to a 200 with an empty page.
 */
@Service
public class LegacyRequestGuard {

	private final LegacyEmployeeRepository legacyEmployeeRepository;
	private final LegacyCompanyRepository legacyCompanyRepository;
	private final TenantFilterActivator tenantFilterActivator;
	private final TenantScope tenantScope;

	public LegacyRequestGuard(
			LegacyEmployeeRepository legacyEmployeeRepository, LegacyCompanyRepository legacyCompanyRepository,
			TenantFilterActivator tenantFilterActivator, TenantScope tenantScope) {
		this.legacyEmployeeRepository = legacyEmployeeRepository;
		this.legacyCompanyRepository = legacyCompanyRepository;
		this.tenantFilterActivator = tenantFilterActivator;
		this.tenantScope = tenantScope;
	}

	/**
	 * Legacy's {@code requireAuth($allowed_roles)}
	 * ({@code functions.php:482-498}): session-validity is checked
	 * unconditionally; the role check only when {@code allowedRoles} is
	 * non-empty (list/one pass none -- any authenticated employee).
	 * {@code TenantScope} establishment is not part of legacy's own
	 * guard stack (PHP has no re-derivation step at all) -- it is Phase
	 * 1's own addition (ADR-0012), checked here because this is the one
	 * place every endpoint already passes through before touching a
	 * company id.
	 *
	 * @throws ApiException 401 {@code unauthorized_no_token} if there is
	 *         no legacy principal on the request at all; 401 {@code
	 *         unauthorized_invalid_token} if the token identifies no
	 *         employee row (or a non-positive id), or if the claimed tenant
	 *         was never re-derived and vouched for by {@code
	 *         LegacyTenantContextService}; 401 {@code session_replaced} if
	 *         the employee exists but its current {@code token_version} does
	 *         not match the token's; 403 {@code forbidden_insufficient_role}
	 *         if roles are given and the token's role claim is not among them
	 */
	@Transactional
	public LegacyRequestContext requireAuth(LegacyEmployee.Role... allowedRoles) {
		AuthenticatedPrincipal principal = currentPrincipal();
		requireSessionValid(principal);

		LegacyEmployee.Role role = parseRole(principal.claimedRole());
		if (allowedRoles.length > 0 && !contains(allowedRoles, role)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "forbidden_insufficient_role");
		}
		if (!tenantScope.isEstablished()) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized_invalid_token");
		}
		return new LegacyRequestContext(principal.identityId(), tenantScope.current(), role);
	}

	/**
	 * Legacy's {@code requireCompanyActive($company_id)}
	 * ({@code functions.php:587-600}).
	 *
	 * @throws ApiException 403 {@code company_account_not_active}
	 */
	public void requireCompanyActive(long companyId) {
		String status = legacyCompanyRepository.findById(companyId)
				.map(LegacyCompany::getStatus)
				.orElse(null);
		if (!"active".equals(status)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "company_account_not_active");
		}
	}

	/**
	 * Legacy's {@code requireEmployeeSessionValid()}
	 * ({@code functions.php:505-521}): the token's {@code token_version}
	 * claim must equal {@code employees.token_version} right now, not at
	 * issuance. A missing claim defaults to {@code -1}, matching PHP's
	 * {@code ?? -1} coalesce, which can never equal a real {@code
	 * token_version} (legacy's column starts at 1 and only increments).
	 * Deliberately tenant-blind (see class javadoc) -- {@code
	 * deactivateForPreTenantLookup()} the same way {@code
	 * LegacyTenantContextService#validate} and {@code
	 * LegacyRefreshTokenService#rotate} already do for their own
	 * pre-trust lookups.
	 */
	private void requireSessionValid(AuthenticatedPrincipal principal) {
		// PHP fails in three distinguishable ways, and the codes are not
		// interchangeable: a non-positive employee id and a missing employee
		// row are both unauthorized_invalid_token ("this token does not
		// identify anybody"), while only a real employee whose token_version
		// moved on is session_replaced ("you were signed out elsewhere").
		// Collapsing the missing row into session_replaced told a deleted
		// employee's client to re-authenticate against an account that no
		// longer exists.
		if (principal.identityId() <= 0) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized_invalid_token");
		}
		tenantFilterActivator.deactivateForPreTenantLookup();
		LegacyEmployee employee = legacyEmployeeRepository.findById(principal.identityId()).orElse(null);
		if (employee == null) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized_invalid_token");
		}
		// (int) ($row['token_version'] ?? 0) -- a null column reads as 0 and
		// can therefore only match a claim of 0, never a real version.
		Integer currentVersion = employee.getTokenVersion();
		long databaseVersion = currentVersion == null ? 0L : currentVersion.longValue();
		long claimedVersion = principal.claimedTokenVersion() == null ? -1L : principal.claimedTokenVersion();
		if (claimedVersion != databaseVersion) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "session_replaced");
		}
	}

	private static AuthenticatedPrincipal currentPrincipal() {
		if (SecurityContextHolder.getContext().getAuthentication() != null
				&& SecurityContextHolder.getContext().getAuthentication().getPrincipal()
						instanceof AuthenticatedPrincipal principal) {
			return principal;
		}
		throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized_no_token");
	}

	/** Unparseable/missing role claims deny by default rather than throwing -- the caller decides what that means. */
	private static LegacyEmployee.Role parseRole(String claimedRole) {
		try {
			return LegacyValues.toEnum(LegacyEmployee.Role.class, claimedRole);
		} catch (RuntimeException ex) {
			return null;
		}
	}

	private static boolean contains(LegacyEmployee.Role[] roles, LegacyEmployee.Role role) {
		if (role == null) {
			return false;
		}
		for (LegacyEmployee.Role candidate : roles) {
			if (candidate == role) {
				return true;
			}
		}
		return false;
	}

}
