package com.workin.backend.platformadmin.content;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.platformadmin.PlatformAdminAuditEventType;
import com.workin.backend.platformadmin.PlatformAdminAuditService;
import com.workin.backend.platformadmin.web.DashboardSession;

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
 *
 * <p><b>The send is deliberately cross-tenant; the delete is not.</b> A
 * broadcast writes a notifications row for every employee of every company by
 * design -- that is the page, and one of the four capabilities ADR-0016 found
 * in the PHP dashboard and nowhere else. Its audience comes from a closed enum
 * ({@link BroadcastAudience}), never from a request-supplied company, so a
 * {@link DashboardSession} would have nothing to compare against. A delete is
 * the opposite: one row, named by a posted id, owned by one company. Legacy
 * guards exactly that case and nothing else
 * ({@code pages/notifications/page.php:59-69}), so this does too. That
 * asymmetry is why this service is no longer in
 * {@code AdminTenantGuardCoverageTest}'s cross-tenant list: the list is
 * per-service, and one of these two paths does have a company to check.
 */
@Service
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
	 * The notification rows themselves, as {@code notifications_paginate()}
	 * lists them. {@link #recent()} groups this surface's own sends; this is
	 * the table, and it is what the dashboard shows.
	 */
	public com.workin.backend.platformadmin.web.DashboardPage<BroadcastStore.NotificationRow> list(
			long companyId, String search, String recipientKind, String dateFrom, String dateTo,
			int page, int perPage) {
		return this.store.list(companyId, search, recipientKind, dateFrom, dateTo, page, perPage);
	}

	/**
	 * Removes one notification.
	 *
	 * <p>Behind the same two gates as a send. A delete here is small next to a
	 * broadcast, but it is still a write to another tenant's data from a
	 * platform session, and ADR-0015 prerequisite 7 is about the surface rather
	 * than the size of the action.
	 *
	 * <p>A session scoped to one company may only delete that company's row,
	 * which is legacy's {@code if ($isComp)} branch and its {@code error_db}
	 * flash. Dormant while every session this surface issues is an
	 * administrator's ({@code AdminViewModelAdvice#session}), and the whole
	 * check the day the owner and HR logins arrive (ADR-0016, R-044) -- which is
	 * why it is written now rather than then, when its absence would look like
	 * the page working.
	 */
	@Transactional
	public Result delete(DashboardSession session, long adminId, long id) {
		if (!this.actionsEnabled) {
			return Result.rejected("admin_actions_disabled");
		}
		if (session.isScopedToOneCompany()) {
			Long owner = this.store.companyOf(id);
			if (owner == null || owner != session.companyId()) {
				return Result.rejected("error_db");
			}
		}
		if (!this.store.delete(id)) {
			return Result.rejected("error_not_found");
		}
		this.auditService.recordAction(adminId, PlatformAdminAuditEventType.CONTENT_DELETED,
				TARGET_TYPE, String.valueOf(id), "notification deleted");
		return new Result(true, 0, null);
	}

	/**
	 * @param confirmed the operator ticked the confirmation; without it a
	 *                  multi-recipient send is refused rather than performed
	 * @param companyId required only by {@link BroadcastAudience#COMPANY_EMPLOYEES}
	 */
	@Transactional
	public Result send(long adminId, String audienceValue,
			String title, String body, Long companyId, boolean confirmed) {

		if (!this.actionsEnabled) {
			return Result.rejected("admin_actions_disabled");
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
				TARGET_TYPE, audience.submitted(), "recipients: " + recipients + "; title: " + subject);

		return new Result(true, recipients, null);
	}

}
