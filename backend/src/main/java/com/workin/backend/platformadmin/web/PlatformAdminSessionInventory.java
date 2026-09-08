package com.workin.backend.platformadmin.web;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

import com.workin.backend.platformadmin.PlatformAdminAuditEventType;
import com.workin.backend.platformadmin.PlatformAdminAuditService;

/**
 * Lists and revokes an administrator's own sessions individually (ADR-0015
 * prerequisite 13, delivering ADR-0005's requirement rather than deferring it).
 *
 * <p>A session is a browser session on this surface, held in Spring Session's
 * store; there is no other kind since ADR-0018 removed the bearer API and its
 * token families, so "revoke everything" means exactly what it says.
 *
 * <p>Revocation is scoped to the caller's own id at every entry point. This is
 * deliberately not an operation on other administrators: that would be a
 * privileged action, and ADR-0015 does not permit one yet.
 */
@Service
public class PlatformAdminSessionInventory {

	private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;
	private final PlatformAdminAuditService auditService;

	public PlatformAdminSessionInventory(
			FindByIndexNameSessionRepository<? extends Session> sessionRepository,
			PlatformAdminAuditService auditService) {
		this.sessionRepository = sessionRepository;
		this.auditService = auditService;
	}

	/**
	 * @param id the session identifier, used to revoke it
	 * @param createdAt when the session was established
	 * @param lastAccessedAt when it was last used
	 * @param current whether this is the session making the request
	 */
	public record BrowserSession(String id, Instant createdAt, Instant lastAccessedAt, boolean current) {
	}

	public List<BrowserSession> browserSessions(long platformAdminId, String currentSessionId) {
		Map<String, ? extends Session> sessions =
				this.sessionRepository.findByPrincipalName(String.valueOf(platformAdminId));
		return sessions.values().stream()
			.map(session -> new BrowserSession(session.getId(), session.getCreationTime(),
					session.getLastAccessedTime(), session.getId().equals(currentSessionId)))
			.sorted(Comparator.comparing(BrowserSession::lastAccessedAt).reversed())
			.toList();
	}

	/**
	 * Revokes one browser session belonging to this administrator.
	 *
	 * <p>Ownership is re-checked here rather than trusted from the form: a
	 * session id is an opaque string a caller can put anything in, and without
	 * the check this would revoke any session in the store by id.
	 *
	 * @return whether a session was revoked
	 */
	public boolean revokeBrowserSession(long platformAdminId, String sessionId) {
		boolean owned = this.sessionRepository.findByPrincipalName(String.valueOf(platformAdminId))
				.containsKey(sessionId);
		if (!owned) {
			return false;
		}
		this.sessionRepository.deleteById(sessionId);
		this.auditService.record(platformAdminId, PlatformAdminAuditEventType.LOGOUT,
				"browser session revoked");
		return true;
	}

	/**
	 * Ends this administrator's browser sessions, except optionally the one
	 * making the request.
	 *
	 * <p>{@code null} spares none, which is what a password rotation needs: a
	 * session opened under the old password must not outlive it, or rotating
	 * would not be the thing an operator reaches for when a session is believed
	 * stolen (ADR-0018, and ADR-0015's session decisions it keeps).
	 */
	public void revokeEverything(long platformAdminId, String exceptSessionId) {
		this.sessionRepository.findByPrincipalName(String.valueOf(platformAdminId)).keySet().stream()
			.filter(id -> !id.equals(exceptSessionId))
			.toList()
			.forEach(this.sessionRepository::deleteById);
		this.auditService.record(platformAdminId, PlatformAdminAuditEventType.ALL_SESSIONS_REVOKED,
				exceptSessionId == null ? "every session" : "every other browser session");
	}

}
