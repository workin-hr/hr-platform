package com.workin.backend.platformadmin.web;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.platformadmin.content.GuideVideoAdminService;
import com.workin.backend.platformadmin.content.GuideVideoForm;

/** {@code dashboard/pages/guide_videos/page.php}. */
@Controller
@Profile("phase1-mysql")
public class AdminGuideVideosController {

	private static final String VIEW = "admin/guide-videos";

	private static final String REDIRECT =
			"redirect:" + PlatformAdminWebSecurityConfig.GUIDE_VIDEOS_PATH;

	private final GuideVideoAdminService service;

	public AdminGuideVideosController(GuideVideoAdminService service) {
		this.service = service;
	}

	@AuthenticatedUseCase(reason = "The platform's how-to clips, read by every client. "
			+ "Administrator-only: the table is platform-wide content with no company_id, "
			+ "and PHP gates the page on isAdmin() alone.")
	@GetMapping(PlatformAdminWebSecurityConfig.GUIDE_VIDEOS_PATH)
	public String list(@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			Model model, @RequestParam(required = false) String error) {
		model.addAttribute("videos", this.service.videos());
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("factorBound", principal.factorBound());
		model.addAttribute("errorKey", error);
		return VIEW;
	}

	@AuthenticatedUseCase(reason = "Adds, edits or removes one guide video. Gated again in the "
			+ "service by the surface flag and a bound second factor, and audited. The filename "
			+ "is held to the same allow-list the read path applies.")
	@PostMapping(PlatformAdminWebSecurityConfig.GUIDE_VIDEOS_PATH)
	public String submit(@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			@RequestParam String action,
			@RequestParam(required = false) Long id,
			@RequestParam(name = "title_ar", required = false) String titleAr,
			@RequestParam(name = "title_en", required = false) String titleEn,
			@RequestParam(required = false) String video,
			@RequestParam(name = "sort_order", required = false) String sortOrder,
			@RequestParam(name = "is_active", required = false) String isActive) {

		long adminId = principal.platformAdminId();
		boolean bound = principal.factorBound();
		// `!empty($post['is_active'])` -- the checkbox is absent when unticked.
		boolean active = isActive != null && !isActive.isBlank();

		GuideVideoAdminService.Result result = switch (action) {
			case "add" -> this.service.create(adminId, bound,
					GuideVideoForm.validate(titleAr, titleEn, video, sortOrder, active));
			case "edit" -> this.service.update(adminId, bound, id == null ? 0L : id,
					GuideVideoForm.validate(titleAr, titleEn, video, sortOrder, active));
			case "delete" -> this.service.delete(adminId, bound, id == null ? 0L : id);
			default -> new GuideVideoAdminService.Result(false, "error_not_found");
		};

		return result.ok() ? REDIRECT : REDIRECT + "?error=" + result.errorKey();
	}

}
