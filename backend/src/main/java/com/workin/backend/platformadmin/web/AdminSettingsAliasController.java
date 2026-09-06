package com.workin.backend.platformadmin.web;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.workin.backend.authorization.AuthenticatedUseCase;

/**
 * {@code dashboard/pages/app_content/page.php} and
 * {@code dashboard/pages/setting_templates/page.php}.
 *
 * <p>Five, ten and fifteen lines each in legacy, all doing the same thing: 302
 * into a tab of the settings page. They are ported as routes rather than
 * folded away because they <em>are</em> routes -- the committed page manifest
 * lists all three, a bookmark reaches them, and deleting them would be a
 * change to the surface rather than a simplification of it.
 *
 * <p>{@code content} differs from the other two and the difference is kept:
 * it calls {@code requireLogin()} and redirects, with no {@code isAdmin()}
 * check, so any signed-in session may follow it. Nothing is disclosed by
 * that -- the settings page itself refuses a non-administrator -- and the
 * asymmetry is legacy's rather than an oversight to correct here.
 */
@Controller
@Profile("phase1-mysql")
public class AdminSettingsAliasController {

	@AuthenticatedUseCase(reason = "Redirects into the settings page's content tab, carrying an "
			+ "optional section. Administrator-only, like the page it lands on.")
	@GetMapping(PlatformAdminWebSecurityConfig.APP_CONTENT_PATH)
	public String appContent(
			Model model, @RequestParam(required = false) String section) {
		if (notAdmin(model)) {
			return "redirect:" + PlatformAdminWebSecurityConfig.PATH_PREFIX;
		}
		// Legacy passes the section through http_build_query only when it is
		// non-empty, and does not check it against the known keys -- the page
		// it lands on does that. What matters is that it is encoded, so a
		// crafted section cannot append a parameter of its own; Spring's
		// redirect view encodes the string it is given, so encoding here as
		// well would double it and turn %26 into %2526.
		String trimmed = section == null ? "" : section.trim();
		String extra = trimmed.isEmpty() ? "" : "&section=" + trimmed;
		return "redirect:" + PlatformAdminWebSecurityConfig.SETTINGS_PATH
				+ "?tab=app_content" + extra;
	}

	@AuthenticatedUseCase(reason = "Redirects into the settings page's templates tab. "
			+ "Administrator-only, like the page it lands on.")
	@GetMapping(PlatformAdminWebSecurityConfig.SETTING_TEMPLATES_PATH)
	public String settingTemplates(Model model) {
		if (notAdmin(model)) {
			return "redirect:" + PlatformAdminWebSecurityConfig.PATH_PREFIX;
		}
		return "redirect:" + PlatformAdminWebSecurityConfig.SETTINGS_PATH
				+ "?tab=setting_templates";
	}

	@AuthenticatedUseCase(reason = "Redirects into the settings page's content tab. Unlike its "
			+ "two siblings this one does not check for an administrator, which is legacy's "
			+ "behaviour; the page it lands on does the checking.")
	@GetMapping(PlatformAdminWebSecurityConfig.CONTENT_PATH)
	public String content() {
		return "redirect:" + PlatformAdminWebSecurityConfig.SETTINGS_PATH + "?tab=app_content";
	}

	private static boolean notAdmin(Model model) {
		DashboardSession session = (DashboardSession) model.getAttribute("session");
		return session == null || !session.isAdmin();
	}

}
