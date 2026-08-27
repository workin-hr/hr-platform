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
		return new LegacyRequestContext(employeeId, tenantScope.current(), role);
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
