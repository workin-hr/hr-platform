package com.workin.backend.platformadmin.web;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.platformadmin.content.FaqAdminService;
import com.workin.backend.platformadmin.content.FaqForm;

/**
 * {@code dashboard/pages/faqs/page.php} -- the help content both clients
 * read through {@code faqs/list}.
 *
 * <p>One page over two tables, six actions, dispatched on an {@code action}
 * field as the dashboard does. Redirect-after-post throughout.
 */
@Controller
@Profile("phase1-mysql")
public class AdminFaqsController {

	private static final String VIEW = "admin/faqs";

	private static final String REDIRECT = "redirect:" + PlatformAdminWebSecurityConfig.FAQS_PATH;

	private final FaqAdminService service;

	public AdminFaqsController(FaqAdminService service) {
		this.service = service;
	}

	@AuthenticatedUseCase(reason = "The platform's FAQ catalogue, read by every client. "
			+ "Only a platform administrator may see or change it.")
	@GetMapping(PlatformAdminWebSecurityConfig.FAQS_PATH)
	public String list(@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			Model model, @RequestParam(required = false) String error) {
		model.addAttribute("categories", this.service.categories());
		model.addAttribute("items", this.service.items());
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("factorBound", principal.factorBound());
		model.addAttribute("errorKey", error);
		return VIEW;
	}

	@AuthenticatedUseCase(reason = "Changes one FAQ category or item. Gated again in the "
			+ "service by the surface flag and a bound second factor, and audited.")
	@PostMapping(PlatformAdminWebSecurityConfig.FAQS_PATH)
	public String submit(@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			@RequestParam String action,
			@RequestParam(required = false) Long id,
			@RequestParam(required = false) String nameAr,
			@RequestParam(required = false) String nameEn,
			@RequestParam(required = false) String categoryId,
			@RequestParam(required = false) String questionAr,
			@RequestParam(required = false) String questionEn,
			@RequestParam(required = false) String answerAr,
			@RequestParam(required = false) String answerEn,
			@RequestParam(required = false) String platform,
			@RequestParam(required = false) String sortOrder,
			@RequestParam(required = false) String isActive) {

		long adminId = principal.platformAdminId();
		boolean bound = principal.factorBound();
		boolean active = isActive != null && !isActive.isBlank();

		FaqAdminService.Result result = switch (action) {
			case "add_category" -> this.service.createCategory(adminId, bound,
					FaqForm.validateCategory(nameAr, nameEn, sortOrder, active));
			case "edit_category" -> id == null ? notFound()
					: this.service.updateCategory(adminId, bound, id,
							FaqForm.validateCategory(nameAr, nameEn, sortOrder, active));
			case "delete_category" -> id == null ? notFound()
					: this.service.deleteCategory(adminId, bound, id);
			case "add_item" -> this.service.createItem(adminId, bound,
					FaqForm.validateItem(categoryId, questionAr, questionEn, answerAr, answerEn,
							platform, sortOrder, active));
			case "edit_item" -> id == null ? notFound()
					: this.service.updateItem(adminId, bound, id,
							FaqForm.validateItem(categoryId, questionAr, questionEn, answerAr, answerEn,
									platform, sortOrder, active));
			case "delete_item" -> id == null ? notFound() : this.service.deleteItem(adminId, bound, id);
			default -> notFound();
		};

		return result.ok() ? REDIRECT : REDIRECT + "?error=" + result.errorKey();
	}

	private static FaqAdminService.Result notFound() {
		return new FaqAdminService.Result(false, "error_not_found");
	}

}
