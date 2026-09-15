package com.workin.backend.platformadmin.web;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.platformadmin.content.Banner;
import com.workin.backend.platformadmin.content.BannerAdminService;
import com.workin.backend.platformadmin.content.BannerForm;
import com.workin.legacy.phone.LegacyPhoneCountries;

/**
 * {@code dashboard/pages/banners/page.php} -- the home-screen cards both
 * clients show.
 *
 * <p>The only admin page that accepts a file. It goes to
 * {@code LegacyFileUploads}, so the stored extension comes from the
 * sniffed content type (D-154) rather than the client's filename, which is
 * what the dashboard's own helper does.
 */
@Controller
public class AdminBannersController {

	private static final String VIEW = "admin/banners";

	private static final String REDIRECT = "redirect:" + PlatformAdminWebSecurityConfig.BANNERS_PATH;

	private final BannerAdminService service;

	private final LegacyPhoneCountries phoneCountries;

	public AdminBannersController(BannerAdminService service, LegacyPhoneCountries phoneCountries) {
		this.service = service;
		this.phoneCountries = phoneCountries;
	}

	@AuthenticatedUseCase(reason = "The platform's home-screen banners, shown to every client. "
			+ "Only a platform administrator may see or change them.")
	@GetMapping(PlatformAdminWebSecurityConfig.BANNERS_PATH)
	public String list(@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			Model model, @RequestParam(required = false) String error,
			@RequestParam(required = false) Long edit) {
		List<Banner> banners = this.service.list();
		model.addAttribute("banners", banners);
		// D-210: `case "edit"` has always been handled and nothing rendered a
		// form that posts it. Prefilled here rather than by crud.js, which
		// fills a field named `title_ar` from `data-title-ar` -- this form's
		// fields are camelCase, so the copied script cannot reach them.
		Banner editBanner = edit == null ? null
				: banners.stream().filter(banner -> banner.id() == edit).findFirst().orElse(null);
		model.addAttribute("editBanner", editBanner);
		// D-230: a WhatsApp button stores its dial code and number as one string
		// of digits, and the form edits them as two inputs, so they are split
		// back for it. Left empty, the save reads no number and stores null.
		model.addAttribute("whatsappParts",
				editBanner != null && editBanner.buttonActionType() == Banner.Action.WHATSAPP
						? BannerForm.splitWhatsapp(editBanner.buttonActionValue(), this.phoneCountries.dialCodes())
						: null);
		model.addAttribute("routes", Banner.INTERNAL_ROUTES);
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("errorKey", error);
		return VIEW;
	}

	@AuthenticatedUseCase(reason = "Adds, edits or removes one banner, including its image. "
			+ "Gated again in the service by the surface flag and a bound second factor, and audited.")
	@PostMapping(PlatformAdminWebSecurityConfig.BANNERS_PATH)
	public String submit(@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			@RequestParam String action,
			@RequestParam(required = false) Long id,
			@RequestParam(required = false) MultipartFile image,
			@RequestParam(required = false) String titleAr,
			@RequestParam(required = false) String titleEn,
			@RequestParam(required = false) String descriptionAr,
			@RequestParam(required = false) String descriptionEn,
			@RequestParam(required = false) String buttonLabelAr,
			@RequestParam(required = false) String buttonLabelEn,
			@RequestParam(required = false) String platform,
			@RequestParam(required = false) String actionType,
			@RequestParam(required = false) String actionValue,
			@RequestParam(required = false) String whatsappCountryCode,
			@RequestParam(required = false) String whatsappPhone,
			@RequestParam(required = false) String isActive,
			@RequestParam(required = false) String sortOrder,
			Model model, RedirectAttributes redirect) {

		long adminId = principal.platformAdminId();
		BannerAdminService.Submission submission = new BannerAdminService.Submission(
				titleAr, titleEn, descriptionAr, descriptionEn, buttonLabelAr, buttonLabelEn,
				platform, actionType, actionValue, whatsappCountryCode, whatsappPhone,
				isActive != null && !isActive.isBlank(), sortOrder);

		BannerAdminService.Result result = switch (action) {
			case "add" -> this.service.create(adminId, image, submission);
			case "edit" -> id == null ? notFound()
					: this.service.update(adminId, id, image, submission);
			case "delete" -> id == null ? notFound() : this.service.delete(adminId, id);
			default -> notFound();
		};

		if (!result.ok()) {
			return REDIRECT + "?error=" + result.errorKey();
		}
		switch (action) {
			case "delete" -> AdminFlash.deleted(redirect, model);
			default -> AdminFlash.saved(redirect, model);
		}
		return REDIRECT;
	}

	private static BannerAdminService.Result notFound() {
		return new BannerAdminService.Result(false, "error_not_found");
	}

}
