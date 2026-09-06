package com.workin.backend.platformadmin.content;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.platformadmin.PlatformAdminAuditEventType;
import com.workin.backend.platformadmin.PlatformAdminAuditService;

/**
 * Sends platform broadcasts -- {@code dashboard/pages/notifications}'s
 * admin half.
 *
 * <p>Carries one gate the other content pages do not: an explicit
 * confirmation. Every audience here reaches thousands of people at once and
 * nothing un-sends a notification, so the dashboard requires the operator
 * to tick a box and this requires the same. It is not step-up -- a TOTP
 * would not tell them how many people they are about to write to, and the
 * count is the thing that changes the decision.
 */
@Service
@Profile("phase1-mysql")
public class BroadcastAdminService {

	static final String TARGET_TYPE = "BROADCAST";

	/**
	 * @param recipients how many rows were written, or 0 on refusal
	 * @param errorKey   the message key to render, or null on success
	 */
	public record Result(boolean ok, int recipients, String errorKey) {

		static Result rejected(String errorKey) {
			return new Result(false, 0, errorKey);
		}
	}

	private final BroadcastStore store;

	private final PlatformAdminAuditService auditService;

	private final boolean actionsEnabled;

	public BroadcastAdminService(BroadcastStore store, PlatformAdminAuditService auditService,
			@Value("${app.platform-admin.actions.enabled:false}") boolean actionsEnabled) {
		this.store = store;
		this.auditService = auditService;
		this.actionsEnabled = actionsEnabled;
	}

	public boolean actionsEnabled() {
		return this.actionsEnabled;
	}

	public int reachOfAllEmployees() {
		return this.store.countAllEmployees();
	}

	public List<BroadcastStore.SentBroadcast> recent() {
		return this.store.recentBroadcasts(20);
	}

	/**
	 * @param confirmed the operator ticked the confirmation; without it a
	 *                  multi-recipient send is refused rather than performed
	 * @param companyId required only by {@link BroadcastAudience#COMPANY_EMPLOYEES}
	 */
	@Transactional
	public Result send(long adminId, boolean factorBound, String audienceValue,
			String title, String body, Long companyId, boolean confirmed) {

		if (!this.actionsEnabled) {
			return Result.rejected("admin_actions_disabled");
		}
		if (!factorBound) {
			return Result.rejected("mfa_required_for_actions");
		}

		BroadcastAudience audience = BroadcastAudience.of(audienceValue);
		if (audience == null) {
			return Result.rejected("error_required");
		}

		String subject = title == null ? "" : title.trim();
		if (subject.isEmpty()) {
			return Result.rejected("error_required");
		}
		// PHP stores an empty body as NULL rather than an empty string, and the
		// clients render the two differently.
		String message = body == null || body.isBlank() ? null : body.trim();

		if (audience.requiresConfirmation() && !confirmed) {
			return Result.rejected("confirm_broadcast");
		}

		int recipients = switch (audience) {
			case ALL_EMPLOYEES -> this.store.broadcastToAllEmployees(subject, message);
			case COMPANY_EMPLOYEES -> {
				if (companyId == null || companyId < 1 || !this.store.companyExists(companyId)) {
					yield -1;
				}
				yield this.store.broadcastToCompanyEmployees(companyId, subject, message);
			}
		};
		if (recipients < 0) {
			return Result.rejected("error_not_found");
		}

		// Audited even when it reached nobody: "the broadcast went out and
		// nobody has it" is the question this row answers.
		this.auditService.recordAction(adminId, PlatformAdminAuditEventType.CONTENT_CREATED,
				TARGET_TYPE, audience.submitted(),
				null, "recipients: " + recipients + "; title: " + subject);

		return new Result(true, recipients, null);
	}

}
