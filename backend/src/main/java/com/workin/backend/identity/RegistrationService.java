package com.workin.backend.identity;

import jakarta.persistence.EntityManager;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import com.workin.backend.tenancy.MembershipRoleAssignment;
import com.workin.backend.tenancy.MembershipRoleRepository;
import com.workin.backend.tenancy.TenantMembership;
import com.workin.backend.tenancy.TenantMembershipRepository;
import com.workin.backend.tenancy.TenantRole;

/**
 * Company self-registration: creates the company, the registering
 * identity, a tenant_membership, and a COMPANY_ADMIN role assignment in
 * one transaction. tenant_memberships/membership_roles are RLS-protected
 * (rls/V5__enable_row_level_security.sql) with no explicit WITH CHECK
 * clause -- Postgres falls back to using the USING expression for
 * inserts too, so app.current_company_id must be set to the brand-new
 * company's id <em>before</em> those two inserts, or RLS rejects them.
 */
@Service
public class RegistrationService {

	private final CompanyRepository companyRepository;
	private final IdentityRepository identityRepository;
	private final TenantMembershipRepository tenantMembershipRepository;
	private final MembershipRoleRepository membershipRoleRepository;
	private final PasswordEncoder passwordEncoder;
	private final EntityManager entityManager;

	public RegistrationService(
			CompanyRepository companyRepository,
			IdentityRepository identityRepository,
			TenantMembershipRepository tenantMembershipRepository,
			MembershipRoleRepository membershipRoleRepository,
			PasswordEncoder passwordEncoder,
			EntityManager entityManager) {
		this.companyRepository = companyRepository;
		this.identityRepository = identityRepository;
		this.tenantMembershipRepository = tenantMembershipRepository;
		this.membershipRoleRepository = membershipRoleRepository;
		this.passwordEncoder = passwordEncoder;
		this.entityManager = entityManager;
	}

	@Transactional
	public Registered register(RegisterCompanyRequest request) {
		if (companyRepository.existsByPhone(request.phone()) || identityRepository.existsByPhone(request.phone())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Phone number already registered");
		}

		// The precheck above is only a fast path -- it does not close the
		// race window between two concurrent registrations for the same
		// phone. companies.phone/identities.phone are UNIQUE (V1/V2), and
		// GenerationType.IDENTITY forces an immediate INSERT on save() (the
		// generated id must come back), so a losing concurrent request
		// throws here, inside this try block, rather than later at
		// transaction commit. Translate that into the same clean 409 the
		// precheck already returns, instead of an uncaught 500.
		Company company;
		Identity identity;
		try {
			company = companyRepository.save(new Company(request.name(), request.phone()));
			identity = identityRepository.save(
					new Identity(request.phone(), passwordEncoder.encode(request.password())));
		} catch (DataIntegrityViolationException ex) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Phone number already registered", ex);
		}

		setTenantSessionVariable(company.getId());

		TenantMembership membership = tenantMembershipRepository.save(
				new TenantMembership(identity.getId(), company.getId()));
		membershipRoleRepository.save(
				new MembershipRoleAssignment(membership.getId(), company.getId(), TenantRole.COMPANY_ADMIN));

		return new Registered(identity.getId(), membership.getId(), company.getId());
	}

	private void setTenantSessionVariable(Long companyId) {
		entityManager
				.createNativeQuery("SELECT set_config('app.current_company_id', :companyId, true)")
				.setParameter("companyId", String.valueOf(companyId))
				.getSingleResult();
	}

	public record Registered(Long identityId, Long membershipId, Long companyId) {
	}

}
