package com.workin.backend.identity;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

import com.workin.backend.i18n.ApiException;
import com.workin.backend.i18n.MessageKeys;
import com.workin.backend.tenancy.IdentityMembershipIndexService;
import com.workin.backend.tenancy.IdentityMembershipIndexService.MembershipSummary;

/**
 * Authenticates an identity by phone/password, then resolves which
 * tenant membership to issue a token for. This first slice picks the
 * single membership a self-registered identity has -- selecting among
 * multiple real memberships (docs/adr/ADR-0010-authorization-model.md
 * Dimension 1: "an identity may have memberships in multiple tenants")
 * is real, separate follow-up work, not implemented here.
 */
@Profile("!phase1-mysql")
@Service
public class LoginService {

	private final IdentityRepository identityRepository;
	private final IdentityMembershipIndexService membershipIndexService;
	private final PasswordEncoder passwordEncoder;

	public LoginService(
			IdentityRepository identityRepository,
			IdentityMembershipIndexService membershipIndexService,
			PasswordEncoder passwordEncoder) {
		this.identityRepository = identityRepository;
		this.membershipIndexService = membershipIndexService;
		this.passwordEncoder = passwordEncoder;
	}

	public Authenticated login(LoginRequest request) {
		Identity identity = identityRepository.findByPhone(request.phone())
				.filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
				.filter(Identity::isActive)
				.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, MessageKeys.AUTH_INVALID_CREDENTIALS));

		List<MembershipSummary> memberships = membershipIndexService.findMembershipsForIdentity(identity.getId());
		if (memberships.isEmpty()) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, MessageKeys.AUTH_NO_ACTIVE_MEMBERSHIP);
		}
		if (memberships.size() > 1) {
			throw new ApiException(HttpStatus.CONFLICT, MessageKeys.AUTH_MULTIPLE_MEMBERSHIPS);
		}
		MembershipSummary membership = memberships.get(0);

		return new Authenticated(identity.getId(), membership.membershipId(), membership.companyId());
	}

	public record Authenticated(Long identityId, Long membershipId, Long companyId) {
	}

}
