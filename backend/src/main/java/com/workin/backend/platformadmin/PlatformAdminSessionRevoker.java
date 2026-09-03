package com.workin.backend.platformadmin;

import java.util.Set;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/**
 * Ends an administrator's sessions across both surfaces at once.
 *
 * <p>Two stores, and forgetting either makes the operation a lie: browser
 * sessions live in Spring Session, API sessions are refresh-token families.
 * "Revoke everything" that cleared only one would leave a working credential
 * behind while telling the operator otherwise.
 *
 * <p>Lives in the platform-admin package rather than the web one because the
 * MFA reset needs it too, and a factor reset that left old sessions alive would
 * defeat the recovery it is part of.
 */
@Component
public class PlatformAdminSessionRevoker {

	private final FindByIndexNameSessionRepository<? extends Session> browserSessions;
	private final PlatformAdminSessionService apiSessions;

	public PlatformAdminSessionRevoker(
			FindByIndexNameSessionRepository<? extends Session> browserSessions,
			PlatformAdminSessionService apiSessions) {
		this.browserSessions = browserSessions;
		this.apiSessions = apiSessions;
	}

	public Set<String> browserSessionIds(long platformAdminId) {
		return this.browserSessions.findByPrincipalName(String.valueOf(platformAdminId)).keySet();
	}

	public void revokeBrowserSession(String sessionId) {
		this.browserSessions.deleteById(sessionId);
	}

	/**
	 * @param exceptSessionId the session to keep, or null to end every one
	 */
	public void revokeEverything(long platformAdminId, String exceptSessionId) {
		browserSessionIds(platformAdminId).stream()
			.filter(id -> !id.equals(exceptSessionId))
			.toList()
			.forEach(this::revokeBrowserSession);
		this.apiSessions.revokeAllForPlatformAdmin(platformAdminId);
	}

}
