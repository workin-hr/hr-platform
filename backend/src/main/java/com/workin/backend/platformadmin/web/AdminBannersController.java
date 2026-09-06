package com.workin.backend.platformadmin.web;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.platformadmin.content.Banner;
import com.workin.backend.platformadmin.content.BannerAdminService;

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
@Profile("phase1-mysql")
public class AdminBannersController {

	private static final String VIEW = "admin/banners";

	private static final String REDIRECT = "redirect:" + PlatformAdminWebSecurityConfig.BANNERS_PATH;

	private final BannerAdminService service;

	public AdminBannersController(BannerAdminService service) {
		this.service = service;
	}

	@AuthenticatedUseCase(reason = "The platform's home-screen banners, shown to every client. "
			+ "Only a platform administrator may see or change them.")
	@GetMapping(PlatformAdminWebSecurityConfig.BANNERS_PATH)
	public String list(@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			Model model, @RequestParam(required = false) String error) {
		model.addAttribute("banners", this.service.list());
		model.addAttribute("routes", Banner.INTERNAL_ROUTES);
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("factorBound", principal.factorBound());
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
			@RequestParam(required = false) String sortOrder) {

		long adminId = principal.platformAdminId();
		boolean bound = principal.factorBound();
		BannerAdminService.Submission submission = new BannerAdminService.Submission(
				titleAr, titleEn, descriptionAr, descriptionEn, buttonLabelAr, buttonLabelEn,
				platform, actionType, actionValue, whatsappCountryCode, whatsappPhone,
				isActive != null && !isActive.isBlank(), sortOrder);

		BannerAdminService.Result result = switch (action) {
			case "add" -> this.service.create(adminId, bound, image, submission);
			case "edit" -> id == null ? notFound()
					: this.service.update(adminId, bound, id, image, submission);
			case "delete" -> id == null ? notFound() : this.service.delete(adminId, bound, id);
			default -> notFound();
		};

		return result.ok() ? REDIRECT : REDIRECT + "?error=" + result.errorKey();
	}

	private static BannerAdminService.Result notFound() {
		return new BannerAdminService.Result(false, "error_not_found");
	}

}
