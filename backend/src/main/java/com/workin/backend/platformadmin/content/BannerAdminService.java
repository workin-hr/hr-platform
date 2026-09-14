package com.workin.backend.platformadmin.content;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.workin.backend.platformadmin.PlatformAdminAuditEventType;
import com.workin.backend.platformadmin.PlatformAdminAuditService;
import com.workin.legacy.phone.LegacyPhoneCountries;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.uploads.LegacyFileUploads;

/**
 * The write side of {@code banners}.
 *
 * <p>Uploads go through {@link LegacyFileUploads}, the same component the
 * ported API endpoints use, so this page inherits **D-154**: the stored
 * extension comes from the sniffed content type, never from the client's
 * filename. The dashboard's own upload helper takes it from the filename
 * (R-039's second instance), and writing a second uploader here is exactly
 * how that defect would have been reproduced.
 */
@Service
public class BannerAdminService {

	static final String TARGET_TYPE = "BANNER";

	/** The dashboard's subdirectory under the upload root. */
	private static final String SUBDIRECTORY = "banners";

	/** @param errorKey the message key to render, or null on success */
	public record Result(boolean ok, String errorKey) {

		static final Result DONE = new Result(true, null);

		static Result rejected(String errorKey) {
			return new Result(false, errorKey);
		}
	}

	private final BannerStore store;

	private final LegacyFileUploads uploads;

	private final PlatformAdminAuditService auditService;

	private final LegacyPhoneCountries phoneCountries;

	private final boolean actionsEnabled;

	public BannerAdminService(BannerStore store, LegacyFileUploads uploads,
			PlatformAdminAuditService auditService, LegacyPhoneCountries phoneCountries,
			@Value("${app.platform-admin.actions.enabled:false}") boolean actionsEnabled) {
		this.store = store;
		this.uploads = uploads;
		this.auditService = auditService;
		this.phoneCountries = phoneCountries;
		this.actionsEnabled = actionsEnabled;
	}

	public boolean actionsEnabled() {
		return this.actionsEnabled;
	}

	public List<Banner> list() {
		return this.store.list();
	}

	@Transactional
	public Result create(long adminId, MultipartFile image, Submission submission) {
		Result gate = gate();
		if (gate != null) {
			return gate;
		}
		String imageUrl;
		try {
			imageUrl = this.uploads.store(image, SUBDIRECTORY);
		} catch (LegacyApiException ex) {
			return Result.rejected("invalid_file_type");
		}
		BannerForm.Result form = submission.validate(imageUrl);
		if (!form.ok()) {
			return Result.rejected(form.errorKey());
		}
		this.store.insert(form.banner());
		audit(adminId, PlatformAdminAuditEventType.CONTENT_CREATED, "new", form.banner());
		return Result.DONE;
	}

	/**
	 * Edits one banner. A submission with no new file keeps the stored image,
	 * which is what {@code banner_resolve_image_url($id)} falls back to --
	 * without it, saving a caption would blank the picture.
	 */
	@Transactional
	public Result update(long adminId, long id,
			MultipartFile image, Submission submission) {
		Result gate = gate();
		if (gate != null) {
			return gate;
		}
		Banner existing = this.store.find(id).orElse(null);
		if (existing == null) {
			return Result.rejected("error_not_found");
		}
		String imageUrl;
		try {
			String uploaded = this.uploads.store(image, SUBDIRECTORY);
			imageUrl = uploaded != null ? uploaded : existing.imageUrl();
		} catch (LegacyApiException ex) {
			return Result.rejected("invalid_file_type");
		}
		BannerForm.Result form = submission.validate(imageUrl);
		if (!form.ok()) {
			return Result.rejected(form.errorKey());
		}
		Banner banner = form.banner();
		// D-230: the form shows a stored WhatsApp number split into two inputs.
		// Posted back as shown, they are that stored number, not a new one built
		// from the parts -- which, for a number no active dial code matches,
		// would gain a leading 20. Only a value legacy would store unchanged --
		// eight to fifteen digits -- is kept that way; any other is rebuilt, as
		// legacy rebuilds every one.
		if (existing.buttonActionType() == Banner.Action.WHATSAPP
				&& banner.buttonActionType() == Banner.Action.WHATSAPP
				&& BannerForm.isStorableWhatsappNumber(existing.buttonActionValue())
				&& submission.showsWhatsapp(BannerForm.splitWhatsapp(
						existing.buttonActionValue(), this.phoneCountries.dialCodes()))) {
			banner = banner.withButtonActionValue(existing.buttonActionValue());
		}
		this.store.update(id, banner);
		audit(adminId, PlatformAdminAuditEventType.CONTENT_UPDATED, String.valueOf(id), banner);
		return Result.DONE;
	}

	@Transactional
	public Result delete(long adminId, long id) {
		Result gate = gate();
		if (gate != null) {
			return gate;
		}
		if (this.store.find(id).isEmpty()) {
			return Result.rejected("error_not_found");
		}
		this.store.delete(id);
		this.auditService.recordAction(adminId, PlatformAdminAuditEventType.CONTENT_DELETED,
				TARGET_TYPE, String.valueOf(id), null);
		return Result.DONE;
	}

	/** @return the refusal, or null when the caller may proceed */
	private Result gate() {
		if (!this.actionsEnabled) {
			return Result.rejected("admin_actions_disabled");
		}
		return null;
	}

	/**
	 * The audit detail names the action a banner carries, because that is the
	 * field with reach: it is what a customer's device opens, and
	 * {@code banners/list} serves it unsanitised.
	 */
	private void audit(long adminId, PlatformAdminAuditEventType type, String targetId, Banner banner) {
		String detail = banner.buttonActionType().stored()
				+ (banner.buttonActionValue() == null ? "" : ": " + banner.buttonActionValue());
		this.auditService.recordAction(adminId, type, TARGET_TYPE, targetId, detail);
	}

	/**
	 * The submitted fields, minus the image, so validation can run once the
	 * upload has resolved to a URL. A record rather than a dozen parameters
	 * repeated on {@code create} and {@code update}.
	 */
	public record Submission(
			String titleAr, String titleEn, String descriptionAr, String descriptionEn,
			String buttonLabelAr, String buttonLabelEn, String platform, String actionType,
			String actionValue, String whatsappCountryCode, String whatsappPhone,
			boolean active, String sortOrder) {

		BannerForm.Result validate(String imageUrl) {
			return BannerForm.validate(imageUrl, this.titleAr, this.titleEn,
					this.descriptionAr, this.descriptionEn, this.buttonLabelAr, this.buttonLabelEn,
					this.platform, this.actionType, this.actionValue,
					this.whatsappCountryCode, this.whatsappPhone, this.active, this.sortOrder);
		}

		/** Whether the WhatsApp inputs came back exactly as the edit form showed them. */
		boolean showsWhatsapp(BannerForm.WhatsappParts shown) {
			return trimmed(this.whatsappCountryCode).equals(shown.countryCode())
					&& trimmed(this.whatsappPhone).equals(shown.local());
		}

		private static String trimmed(String value) {
			return value == null ? "" : value.trim();
		}
	}

}
