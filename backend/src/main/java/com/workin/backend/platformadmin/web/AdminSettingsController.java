package com.workin.backend.platformadmin.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.platformadmin.settings.ConfigValues;
import com.workin.backend.platformadmin.settings.SettingsAdminService;
import com.workin.backend.platformadmin.settings.SettingsAdminStore;
import com.workin.backend.platformadmin.settings.SettingsCatalog;

/** {@code dashboard/pages/settings/page.php}. */
@Controller
public class AdminSettingsController {

	private static final String VIEW = "admin/settings";

	private static final String PATH = PlatformAdminWebSecurityConfig.SETTINGS_PATH;

	private static final List<String> TABS = List.of("app_content", "setting_templates", "system");

	private final SettingsAdminStore store;

	private final SettingsAdminService service;

	public AdminSettingsController(SettingsAdminStore store, SettingsAdminService service) {
		this.store = store;
		this.service = service;
	}

	@AuthenticatedUseCase(reason = "Platform settings in three tabs: the app's fixed content "
			+ "documents, the setting templates every company chooses from, and the system "
			+ "configuration the mobile clients read. Administrator-only -- every table behind "
			+ "it is platform-level with no company_id.")
	@GetMapping(PATH)
	public String page(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			Model model,
			@RequestParam(required = false, defaultValue = "app_content") String tab,
			@RequestParam(required = false) String section,
			@RequestParam(required = false) String error) {

		DashboardSession session = (DashboardSession) model.getAttribute("session");
		if (session == null || !session.isAdmin()) {
			return "redirect:" + PlatformAdminWebSecurityConfig.PATH_PREFIX;
		}

		String selected = TABS.contains(tab) ? tab : "app_content";
		model.addAttribute("tab", selected);
		model.addAttribute("errorKey", error);
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());

		// Only the selected tab's data is loaded, as legacy does -- the other
		// two tabs' queries are not run at all.
		switch (selected) {
			case "app_content" -> {
				model.addAttribute("content", this.store.appContent());
				// List.of() throws on contains(null) rather than answering
				// false, and section is absent on the tab's own first load.
				String active = section != null && SettingsCatalog.APP_CONTENT_KEYS.contains(section)
						? section : "compliance";
				model.addAttribute("activeKey", active);
			}
			case "setting_templates" -> model.addAttribute("templates", this.store.templates());
			default -> {
				Map<String, String> values = this.store.configs();
				model.addAttribute("configValues", values);
				model.addAttribute("configGroups", grouped());
				model.addAttribute("groupOrder", SettingsCatalog.GROUP_ORDER);
				model.addAttribute("inputValues", inputValues(values));
			}
		}
		return VIEW;
	}

	@AuthenticatedUseCase(reason = "Saves one of the settings tabs. Gated by the surface flag "
			+ "and a bound second factor. Content keys are allowlisted; a template option that "
			+ "companies have selected keeps its stored value and cannot be deleted.")
	@PostMapping(PATH)
	public String submit(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request,
			Model model,
			@RequestParam String action) {

		DashboardSession session = (DashboardSession) model.getAttribute("session");
		if (session == null || !session.isAdmin()) {
			return "redirect:" + PlatformAdminWebSecurityConfig.PATH_PREFIX;
		}

		long adminId = principal.platformAdminId();

		try {
			switch (action) {
				case "save_content" -> {
					String key = param(request, "content_key");
					this.service.saveContent(adminId, key,
							param(request, "content_value_ar"),
							param(request, "content_value_en"));
					return redirect("app_content",
							key.isEmpty() ? null : "&section=" + key, null);
				}
				case "edit_definition" -> {
					this.service.editDefinition(adminId, number(request, "id"),
							param(request, "label_ar"), param(request, "label_en"),
							param(request, "description_ar"), param(request, "description_en"),
							(int) number(request, "sort_order"));
					return redirect("setting_templates", null, null);
				}
				case "add_option" -> {
					this.service.addOption(adminId,
							number(request, "setting_definition_id"),
							param(request, "value"), param(request, "label_ar"),
							param(request, "label_en"), (int) number(request, "sort_order"));
					return redirect("setting_templates", null, null);
				}
				case "edit_option" -> {
					this.service.editOption(adminId, number(request, "id"),
							param(request, "value"), param(request, "label_ar"),
							param(request, "label_en"), (int) number(request, "sort_order"));
					return redirect("setting_templates", null, null);
				}
				case "delete_option" -> {
					this.service.deleteOption(adminId, number(request, "id"));
					return redirect("setting_templates", null, null);
				}
				case "save_configs" -> {
					Map<String, String> posted = new LinkedHashMap<>();
					SettingsCatalog.CONFIGS.keySet()
							.forEach(key -> posted.put(key, request.getParameter(key)));
					this.service.saveConfigs(adminId, posted);
					return redirect("system", null, null);
				}
				default -> {
					return redirect("app_content", null, "error_required");
				}
			}
		}
		catch (SettingsAdminService.RefusedException refused) {
			return redirect(tabFor(action), null, messageFor(refused));
		}
	}

	private static String redirect(String tab, String extra, String error) {
		return "redirect:" + PATH + "?tab=" + tab
				+ (extra == null ? "" : extra)
				+ (error == null ? "" : "&error=" + error);
	}

	private static String tabFor(String action) {
		return "save_content".equals(action) ? "app_content"
				: "save_configs".equals(action) ? "system" : "setting_templates";
	}

	/**
	 * Legacy flashes the helper's own message, which for the two interesting
	 * refusals is specific: the value is already taken, or the option is in use
	 * by a stated number of companies. The count cannot ride in a redirect
	 * parameter without inventing a message key, so the shared key is used and
	 * the number stays in the audit line.
	 */
	private static String messageFor(SettingsAdminService.RefusedException refused) {
		return switch (refused.refusal()) {
			case ACTIONS_DISABLED -> "admin_actions_disabled";
			case VALUE_TOO_LONG -> "setting_option_value_too_long";
			case VALUE_EXISTS -> "setting_option_value_exists";
			case OPTION_IN_USE -> "setting_option_in_use_delete_blocked";
			default -> "error_required";
		};
	}

	private static String param(HttpServletRequest request, String name) {
		String value = request.getParameter(name);
		return value == null ? "" : value;
	}

	/** PHP's {@code (int)} cast of a posted field. */
	private static long number(HttpServletRequest request, String name) {
		return com.workin.legacy.LegacyValues.toPhpLong(param(request, name));
	}

	private static Map<String, List<SettingsCatalog.ConfigDefinition>> grouped() {
		Map<String, List<SettingsCatalog.ConfigDefinition>> groups = new LinkedHashMap<>();
		for (String group : SettingsCatalog.GROUP_ORDER) {
			groups.put(group, SettingsCatalog.CONFIGS.values().stream()
					.filter(definition -> definition.group().equals(group)).toList());
		}
		return groups;
	}

	private static Map<String, String> inputValues(Map<String, String> stored) {
		Map<String, String> inputs = new LinkedHashMap<>();
		SettingsCatalog.CONFIGS.forEach((key, definition) -> inputs.put(key,
				ConfigValues.forInput(definition.type(), stored.get(key))));
		return inputs;
	}

}
