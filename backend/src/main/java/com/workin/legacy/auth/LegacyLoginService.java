package com.workin.legacy.auth;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.i18n.ApiException;
import com.workin.backend.identity.JwtService;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.TenantFilterActivator;
import com.workin.legacy.companies.LegacyCompany;
import com.workin.legacy.companies.LegacyCompanyRepository;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.employees.LegacyEmployeeRepository;

/**
 * The legacy employee-login use case (punch-list item #9), owning its
 * own transaction -- moved out of {@link LegacyLoginController} on the
 * repository owner's explicit direction (this conversation, 2026-08-18,
 * closing D-049's Follow-up item (c)): the token-version bump and token
 * issuance are one application use case and should own one
 * service-level transaction, with the controller kept thin. Modeled on
 * {@link LegacyRefreshTokenService} in this same package -- {@code
 * @Service}, one {@code @Transactional} method per use case, constructor
 * injection.
 *
 * <p>{@code @Transactional} (P-7, D-049): the phone lookup, the {@code
 * token_version} bump, and the re-read that embeds the fresh value in
 * the issued token must share one persistence context -- {@link
 * TenantFilterActivator#deactivateForPreTenantLookup()}'s effect is
 * session-scoped, and every separate, un-transacted repository call
 * otherwise opens its own, independently {@code TenantFilter.NO_TENANT}-
 * bound one (pre-login, no tenant scope exists yet) -- exactly the trap
 * {@link LegacyRefreshTokenService#rotate} and {@link
 * LegacyTenantContextService#validate} already avoid by being {@code
 * @Transactional} themselves. One consequence made explicit by this move:
 * a failure anywhere after the {@code token_version} bump -- including
 * token issuance itself -- rolls the bump back along with everything
 * else in this method, so a failed login attempt can never leave a
 * bumped-but-unusable session behind.
 */
@Service
public class LegacyLoginService {

	private static final Logger log = LoggerFactory.getLogger(LegacyLoginService.class);

	private final LegacyEmployeeRepository legacyEmployeeRepository;
	private final LegacyCompanyRepository legacyCompanyRepository;
	private final TenantFilterActivator tenantFilterActivator;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final LegacyRefreshTokenService legacyRefreshTokenService;

	public LegacyLoginService(
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

	@Transactional
	public LegacyAuthResponse login(LegacyLoginRequest request) {
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

		// P-7 (D-045/D-049): mirrors employee_issue_session_token()'s
		// version bump exactly -- invalidate any token issued by a prior
		// login before embedding the fresh version in this one, so a
		// replaced session's old token stops matching employees.token_version.
		legacyEmployeeRepository.bumpTokenVersion(authenticated.employeeId());
		long tokenVersion = legacyEmployeeRepository.findById(authenticated.employeeId())
				.map(LegacyEmployee::getTokenVersion)
				.orElseThrow(() -> new IllegalStateException(
						"Employee " + authenticated.employeeId() + " vanished between login and token issuance"))
				.longValue();

		String accessToken = jwtService.issueAccessToken(
				authenticated.employeeId(), authenticated.employeeId(),
				authenticated.companyId(), session.familyId(),
				Map.of("role", authenticated.role(), "token_version", tokenVersion));

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
