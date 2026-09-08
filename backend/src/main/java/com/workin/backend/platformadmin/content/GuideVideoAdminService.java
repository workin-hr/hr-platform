package com.workin.backend.platformadmin.content;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.platformadmin.PlatformAdminAuditEventType;
import com.workin.backend.platformadmin.PlatformAdminAuditService;

/**
 * The write side of the guide-video catalogue.
 *
 * <p>Same three gates as {@link FaqAdminService} — surface flag, bound second
 * factor, audit in the same transaction — and no tenant check, because
 * {@code guide_videos} has no {@code company_id} and the page is
 * administrator-only. That absence is the reason, not an omission: there is no
 * owning company for a guard to compare against.
 */
@Service
public class GuideVideoAdminService {

	static final String TARGET = "GUIDE_VIDEO";

	/** @param errorKey the message key to render, or null on success */
	public record Result(boolean ok, String errorKey) {

		static final Result DONE = new Result(true, null);

		static Result rejected(String errorKey) {
			return new Result(false, errorKey);
		}
	}

	private final GuideVideoStore store;

	private final PlatformAdminAuditService auditService;

	private final boolean actionsEnabled;

	public GuideVideoAdminService(GuideVideoStore store, PlatformAdminAuditService auditService,
			@Value("${app.platform-admin.actions.enabled:false}") boolean actionsEnabled) {
		this.store = store;
		this.auditService = auditService;
		this.actionsEnabled = actionsEnabled;
	}

	public boolean actionsEnabled() {
		return this.actionsEnabled;
	}

	public List<GuideVideo> videos() {
		return this.store.all();
	}

	@Transactional
	public Result create(long adminId, GuideVideoForm.Result form) {
		Result gate = gate();
		if (gate != null) {
			return gate;
		}
		if (!form.ok()) {
			return Result.rejected(form.errorKey());
		}
		this.store.insert(form.video());
		audit(adminId, PlatformAdminAuditEventType.CONTENT_CREATED, form.video().titleEn(),
				"video: " + form.video().video());
		return Result.DONE;
	}

	/**
	 * {@code edit}.
	 *
	 * <p>PHP checks {@code $id < 1} and flashes {@code error_required}, then
	 * validates, then updates by id with no existence check — an unknown id
	 * updates nothing and still flashes {@code saved_ok}. That last part is not
	 * reproduced: this is the same shape {@link FaqAdminService} already refuses
	 * with {@code error_not_found}, and telling an administrator a row was saved
	 * when nothing was written is a message that can only mislead. The change is
	 * confined to the message; no row is written that PHP would not write, and
	 * none is skipped that PHP would.
	 */
	@Transactional
	public Result update(long adminId, long id, GuideVideoForm.Result form) {
		Result gate = gate();
		if (gate != null) {
			return gate;
		}
		if (id < 1) {
			return Result.rejected("error_required");
		}
		if (!form.ok()) {
			return Result.rejected(form.errorKey());
		}
		String existing = this.store.titleOf(id);
		if (existing == null) {
			return Result.rejected("error_not_found");
		}
		this.store.update(id, form.video());
		audit(adminId, PlatformAdminAuditEventType.CONTENT_UPDATED, String.valueOf(id),
				"video: " + form.video().video());
		return Result.DONE;
	}

	@Transactional
	public Result delete(long adminId, long id) {
		Result gate = gate();
		if (gate != null) {
			return gate;
		}
		// `elseif ($action === 'delete' && (int) ($_POST['id'] ?? 0))` -- a zero
		// id falls through the whole block and only redirects.
		if (id < 1) {
			return Result.rejected("error_required");
		}
		String existing = this.store.titleOf(id);
		if (existing == null) {
			return Result.rejected("error_not_found");
		}
		this.store.delete(id);
		audit(adminId, PlatformAdminAuditEventType.CONTENT_DELETED, String.valueOf(id), existing);
		return Result.DONE;
	}

	private Result gate() {
		if (!this.actionsEnabled) {
			return Result.rejected("admin_actions_disabled");
		}
		return null;
	}

	private void audit(long adminId, PlatformAdminAuditEventType type, String targetId,
			String detail) {
		this.auditService.recordAction(adminId, type, TARGET, targetId, detail);
	}

}
