package com.workin.backend.platformadmin.content;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.platformadmin.PlatformAdminAuditEventType;
import com.workin.backend.platformadmin.PlatformAdminAuditService;

/**
 * The write side of the FAQ catalogue: the capability that existed only in
 * the PHP dashboard until ADR-0016.
 *
 * <p>Same three gates as {@link PhoneCountryAdminService} -- surface flag,
 * bound second factor, audit in the same transaction -- and the same
 * reason for not requiring step-up per edit.
 */
@Service
@Profile("phase1-mysql")
public class FaqAdminService {

	static final String CATEGORY_TARGET = "FAQ_CATEGORY";

	static final String ITEM_TARGET = "FAQ_ITEM";

	/** @param errorKey the message key to render, or null on success */
	public record Result(boolean ok, String errorKey) {

		static final Result DONE = new Result(true, null);

		static Result rejected(String errorKey) {
			return new Result(false, errorKey);
		}
	}

	private final FaqStore store;

	private final PlatformAdminAuditService auditService;

	private final boolean actionsEnabled;

	public FaqAdminService(FaqStore store, PlatformAdminAuditService auditService,
			@Value("${app.platform-admin.actions.enabled:false}") boolean actionsEnabled) {
		this.store = store;
		this.auditService = auditService;
		this.actionsEnabled = actionsEnabled;
	}

	public boolean actionsEnabled() {
		return this.actionsEnabled;
	}

	public List<Faq.Category> categories() {
		return this.store.categories();
	}

	public List<Faq.Item> items() {
		return this.store.items();
	}

	@Transactional
	public Result createCategory(long adminId, boolean factorBound, FaqForm.CategoryResult form) {
		Result gate = gate(factorBound);
		if (gate != null) {
			return gate;
		}
		if (!form.ok()) {
			return Result.rejected(form.errorKey());
		}
		this.store.insertCategory(form.category());
		audit(adminId, PlatformAdminAuditEventType.CONTENT_CREATED, CATEGORY_TARGET,
				form.category().nameEn(), null);
		return Result.DONE;
	}

	@Transactional
	public Result updateCategory(long adminId, boolean factorBound, long id, FaqForm.CategoryResult form) {
		Result gate = gate(factorBound);
		if (gate != null) {
			return gate;
		}
		if (!form.ok()) {
			return Result.rejected(form.errorKey());
		}
		if (!this.store.categoryExists(id)) {
			return Result.rejected("error_not_found");
		}
		this.store.updateCategory(id, form.category());
		audit(adminId, PlatformAdminAuditEventType.CONTENT_UPDATED, CATEGORY_TARGET,
				String.valueOf(id), null);
		return Result.DONE;
	}

	/**
	 * Deleting a category takes its items with it through the schema's own
	 * cascade. The audit row records how many, because "the FAQ section
	 * vanished" is answered by that number and by nothing else.
	 */
	@Transactional
	public Result deleteCategory(long adminId, boolean factorBound, long id) {
		Result gate = gate(factorBound);
		if (gate != null) {
			return gate;
		}
		if (!this.store.categoryExists(id)) {
			return Result.rejected("error_not_found");
		}
		int items = this.store.deleteCategory(id);
		audit(adminId, PlatformAdminAuditEventType.CONTENT_DELETED, CATEGORY_TARGET,
				String.valueOf(id), "cascaded items: " + items);
		return Result.DONE;
	}

	@Transactional
	public Result createItem(long adminId, boolean factorBound, FaqForm.ItemResult form) {
		Result gate = gate(factorBound);
		if (gate != null) {
			return gate;
		}
		if (!form.ok()) {
			return Result.rejected(form.errorKey());
		}
		// The dashboard checks the category exists before inserting, and so
		// must this: the column has a foreign key, so a missing category would
		// otherwise surface as a constraint violation rather than the
		// dashboard's own message.
		if (!this.store.categoryExists(form.item().categoryId())) {
			return Result.rejected("faq_category_not_found");
		}
		this.store.insertItem(form.item());
		audit(adminId, PlatformAdminAuditEventType.CONTENT_CREATED, ITEM_TARGET,
				String.valueOf(form.item().categoryId()), null);
		return Result.DONE;
	}

	@Transactional
	public Result updateItem(long adminId, boolean factorBound, long id, FaqForm.ItemResult form) {
		Result gate = gate(factorBound);
		if (gate != null) {
			return gate;
		}
		if (!form.ok()) {
			return Result.rejected(form.errorKey());
		}
		if (this.store.findItem(id).isEmpty()) {
			return Result.rejected("error_not_found");
		}
		if (!this.store.categoryExists(form.item().categoryId())) {
			return Result.rejected("faq_category_not_found");
		}
		this.store.updateItem(id, form.item());
		audit(adminId, PlatformAdminAuditEventType.CONTENT_UPDATED, ITEM_TARGET, String.valueOf(id), null);
		return Result.DONE;
	}

	@Transactional
	public Result deleteItem(long adminId, boolean factorBound, long id) {
		Result gate = gate(factorBound);
		if (gate != null) {
			return gate;
		}
		if (this.store.findItem(id).isEmpty()) {
			return Result.rejected("error_not_found");
		}
		this.store.deleteItem(id);
		audit(adminId, PlatformAdminAuditEventType.CONTENT_DELETED, ITEM_TARGET, String.valueOf(id), null);
		return Result.DONE;
	}

	/** @return the refusal, or null when the caller may proceed */
	private Result gate(boolean factorBound) {
		if (!this.actionsEnabled) {
			return Result.rejected("admin_actions_disabled");
		}
		if (!factorBound) {
			return Result.rejected("mfa_required_for_actions");
		}
		return null;
	}

	private void audit(long adminId, PlatformAdminAuditEventType type, String targetType,
			String targetId, String detail) {
		this.auditService.recordAction(adminId, type, targetType, targetId, null, detail);
	}

}
