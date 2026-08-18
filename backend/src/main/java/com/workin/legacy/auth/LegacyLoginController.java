package com.workin.legacy.auth;

import java.util.List;
import java.util.Optional;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.workin.backend.i18n.ApiException;
import com.workin.backend.identity.JwtService;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.TenantFilterActivator;
import com.workin.legacy.companies.LegacyCompany;
import com.workin.legacy.companies.LegacyCompanyRepository;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.employees.LegacyEmployeeRepository;

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
 * <p>Runs the phone lookup before any tenant is known, exactly the case
 * {@link TenantFilterActivator#deactivateForPreTenantLookup()} was
 * named for. Outcomes and their status/message-key pairs are
 * {@link LegacyLoginOutcome}'s own contract, ported from
 * {@code login_employee.php:70-107} -- this controller only maps them
 * onto HTTP, it does not re-derive them.
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

	private static final Logger log = LoggerFactory.getLogger(LegacyLoginController.class);

	private final LegacyEmployeeRepository legacyEmployeeRepository;
	private final LegacyCompanyRepository legacyCompanyRepository;
	private final TenantFilterActivator tenantFilterActivator;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final LegacyRefreshTokenService legacyRefreshTokenService;

	public LegacyLoginController(
			LegacyEmployeeRepository legacyEmployeeRepository,
			LegacyCompanyRepository legacyCompanyRepository,
			TenantFilterActivator tenantFilterActivator,
			PasswordEncoder passwordEncoder,
			JwtService jwtService,
			LegacyRefreshTokenService legacyRefreshTokenService) {
		this.legacyEmployeeRepository = legacyEmployeeRepository;
		this.legacyCompanyRepository = legacyCompanyRepository;
		this.tenantFilterActivator = tenantFilterActivator;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.legacyRefreshTokenService = legacyRefreshTokenService;
	}

	@PostMapping("/api/legacy/auth/login_employee")
	public LegacyAuthResponse login(@Valid @RequestBody LegacyLoginRequest request) {
		tenantFilterActivator.deactivateForPreTenantLookup();

		List<LegacyEmployee> candidates = legacyEmployeeRepository.findByPhoneOrderByIdDesc(request.phone());
		List<LegacyLoginCandidate> projected = candidates.stream()
				.map(this::toCandidate)
				.filter(Optional::isPresent)
				.map(Optional::get)
				.toList();

		LegacyLoginResolution resolution = LegacyLoginResolver.resolve(
				projected, hash -> passwordEncoder.matches(request.password(), hash));

		if (!resolution.outcome().isSuccess()) {
			LegacyLoginOutcome outcome = resolution.outcome();
			throw new ApiException(HttpStatus.valueOf(outcome.status()), outcome.messageKey());
		}

		LegacyLoginCandidate authenticated = resolution.authenticated();
		LegacyRefreshTokenService.IssuedLegacyRefreshToken session =
				legacyRefreshTokenService.issue(authenticated.employeeId());
		String accessToken = jwtService.issueAccessToken(
				authenticated.employeeId(), authenticated.employeeId(),
				authenticated.companyId(), session.familyId());

		return new LegacyAuthResponse(
				accessToken, session.rawToken(), authenticated.employeeId(), authenticated.companyId());
	}

	/**
	 * Joins in the employee's company status -- legacy's own login query
	 * is exactly this join ({@code login_employee.php:18-48}), done here
	 * per-candidate since a phone match is rarely more than one or two
	 * rows.
	 *
	 * <p>Empty means the employee references a {@code company_id} that
	 * does not exist. {@code employees} actually carries a real,
	 * enforced {@code fk_employee_company FOREIGN KEY (company_id)
	 * REFERENCES companies (id) ON DELETE CASCADE}
	 * (`mysql_workin.schema.sql:1622-1624`), so this branch is defensive
	 * depth against a defect class this schema already rules out --
	 * confirmed by trying to seed it in
	 * {@code LegacyLoginEndToEndTest}, which fails with
	 * {@code SQLIntegrityConstraintViolationException} rather than
	 * producing the row -- not a fix for a currently reachable
	 * production bug. Kept anyway: a skip-and-log costs nothing here,
	 * degrades correctly if the constraint is ever relaxed, and matches
	 * how the ETL work already treats this same defect class elsewhere
	 * ({@code 2026-08-13-etl-real-data-findings-decision-brief.md}
	 * finding H, orphaned {@code exception_types} rows, a table with no
	 * such constraint).
	 */
	private Optional<LegacyLoginCandidate> toCandidate(LegacyEmployee employee) {
		Optional<LegacyCompany> company = legacyCompanyRepository.findById(employee.getCompanyId());
		if (company.isEmpty()) {
			log.warn(
					"Legacy employee {} references company {}, which does not exist -- excluding this candidate from login",
					employee.getId(), employee.getCompanyId());
			return Optional.empty();
		}
		return Optional.of(new LegacyLoginCandidate(
				employee.getId(),
				employee.getCompanyId(),
				LegacyValues.fromEnum(employee.role()),
				LegacyValues.fromEnum(employee.joinRequestStatus()),
				employee.active(),
				company.get().getStatus(),
				employee.getPasswordHash()));
	}

}
