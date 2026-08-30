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

/** Reproduces PHP requireAuth(), employee session-version and role guards. */
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
	 * PHP requireAuth($allowed_roles): validate token_version only when
	 * type=employee, then optionally validate role. Other signed PHP auth types
	 * deliberately skip the employee-session check exactly as the source does.
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

		long employeeId = principal.legacyAuthType() != null && !"employee".equals(principal.legacyAuthType())
				? 0L : (principal.identityId() == null ? 0L : principal.identityId());
		return new LegacyRequestContext(employeeId, tenantScope.current(), role, principal.legacyAuthType());
	}

	/**
	 * {@code if ($auth = getAuth())} -- an <b>optional</b> authentication.
	 *
	 * <p>Returns null when the request carries no usable token, instead of
	 * answering 401. Only {@code complaints/create.php} needs this: it accepts
	 * anonymous submissions and attaches the employee and company only when a
	 * caller happens to be signed in.
	 *
	 * <h2>Where the boundary actually is</h2>
	 * <p>PHP's {@code getAuth()} ends in {@code jwtDecode()}, which returns
	 * {@code null} for a malformed token, a bad signature, or an expired
	 * {@code exp} ({@code functions.php:435-453}). {@code if ($auth = getAuth())}
	 * is then false and the request proceeds <b>anonymously</b>. So an
	 * unusable token is not an error on this route in legacy, and it is not one
	 * here either -- the filter clears the context and this method returns
	 * null, which is the same outcome.
	 *
	 * <p>What <em>is</em> still enforced is the check that runs <b>after</b> a
	 * successful decode: PHP follows {@code getAuth()} with
	 * {@code requireEmployeeSessionValid($auth)}, so a validly-signed token
	 * whose {@code token_version} has been bumped is <b>rejected with 401</b>
	 * rather than downgraded to anonymous. {@link #requireSessionValid} is
	 * called below for exactly that reason.
	 *
	 * <p>The distinction matters and is easy to state backwards: a token that
	 * cannot be decoded is invisible, while a token that decodes but has been
	 * revoked is refused.
	 *
	 * <p><b>{@code @Transactional} for the same reason {@link #requireAuth} is.</b>
	 * The decode path reaches {@link #requireSessionValid}, which disables the
	 * Hibernate tenant filters through the shared {@code EntityManager}; with
	 * {@code spring.jpa.open-in-view=false} there is no persistence context
	 * outside a transaction, so an <em>authenticated</em> submission would fail
	 * before the insert while an anonymous one took the early return and
	 * succeeded. Optional authentication still needs the boundary the mandatory
	 * one has.
	 */
	@Transactional
	public LegacyRequestContext optionalAuth() {
		if (SecurityContextHolder.getContext().getAuthentication() == null
				|| !(SecurityContextHolder.getContext().getAuthentication().getPrincipal()
						instanceof AuthenticatedPrincipal principal)) {
			return null;
		}
		requireSessionValid(principal);
		if (!tenantScope.isEstablished()) {
			return null;
		}
		long employeeId = principal.legacyAuthType() != null
				&& !"employee".equals(principal.legacyAuthType())
				? 0L : (principal.identityId() == null ? 0L : principal.identityId());
		return new LegacyRequestContext(employeeId, tenantScope.current(),
				parseRole(principal.claimedRole()), principal.legacyAuthType());
	}

	/** PHP requireCompanyActive($company_id). */
	public void requireCompanyActive(long companyId) {
		String status = legacyCompanyRepository.findById(companyId)
				.map(LegacyCompany::getStatus)
				.orElse(null);
		if (!"active".equals(status)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "company_account_not_active");
		}
	}

	/** PHP requireEmployeeSessionValid(): only type=employee participates. */
	private void requireSessionValid(AuthenticatedPrincipal principal) {
		if (principal.legacyAuthType() != null && !"employee".equals(principal.legacyAuthType())) {
			return;
		}
		if (principal.identityId() == null || principal.identityId() <= 0) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized_invalid_token");
		}
		tenantFilterActivator.deactivateForPreTenantLookup();
		LegacyEmployee employee = legacyEmployeeRepository.findById(principal.identityId()).orElse(null);
		if (employee == null) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized_invalid_token");
		}
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
