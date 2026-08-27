package com.workin.backend.security;

/**
 * Claims extracted from an authenticated token before tenant-context
 * validation. The optional legacyAuthType is populated only by the Phase-1
 * PHP-compatibility filter so company and employee tokens can preserve PHP's
 * different session rules without changing the new-platform token model.
 */
public record AuthenticatedPrincipal(
		Long identityId,
		Long claimedMembershipId,
		Long claimedCompanyId,
		String claimedRole,
		Long claimedTokenVersion,
		String legacyAuthType) {

	public AuthenticatedPrincipal(
			Long identityId, Long claimedMembershipId, Long claimedCompanyId,
			String claimedRole, Long claimedTokenVersion) {
		this(identityId, claimedMembershipId, claimedCompanyId, claimedRole, claimedTokenVersion, null);
	}

	public AuthenticatedPrincipal(Long identityId, Long claimedMembershipId, Long claimedCompanyId) {
		this(identityId, claimedMembershipId, claimedCompanyId, null, null, null);
	}
}
