@import java.util.List
@import java.util.Map
@import java.util.function.Function
@import com.workin.backend.platformadmin.settings.AppContentEntry
@import com.workin.backend.platformadmin.settings.SettingTemplate
@import com.workin.backend.platformadmin.settings.SettingsCatalog
@import com.workin.backend.platformadmin.web.DashboardSession
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param String tab
@param boolean actionsEnabled
@param boolean factorBound
@param String errorKey = null
@param List<AppContentEntry> content = null
@param String activeKey = null
@param List<SettingTemplate> templates = null
@param Map<String, String> configValues = null
@param Map<String, String> inputValues = null
@param Map<String, List<SettingsCatalog.ConfigDefinition>> configGroups = null
@param List<String> groupOrder = null
@param String currentPage
@param String lang
@param DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
!{boolean canWrite = actionsEnabled && factorBound;}
<%-- dashboard/pages/settings/page.php. Three tabs over three platform-level
     tables, so only the selected tab's data is loaded -- the other two tabs'
     queries are not run, as legacy does not run them. --%>
@template.admin.layout(
	title = t.apply("nav_settings"),
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentAdminPhone = currentAdminPhone,
	currentPage = currentPage,
	lang = lang,
	session = session,
	t = t,
	available = available,
	pageStyles = "app_content".equals(tab)
		? java.util.List.of("hr-pages.css", "settings.css", "app-content.css")
		: java.util.List.of("hr-pages.css", "settings.css"),
	pageScripts = "app_content".equals(tab) ? java.util.List.of("app-content.js")
		: "setting_templates".equals(tab) ? java.util.List.of("setting-templates.js")
		: java.util.List.<String>of(),
	contentClass = "settings-hub-page hr-page",
	body = @`
	@if(errorKey != null)
		<div class="flash flash-error">${t.apply(errorKey)}</div>
	@endif

	<nav class="settings-hub-tabs" aria-label="${t.apply("nav_settings")}">
		<a href="/admin/settings?tab=app_content"
		   class="settings-hub-tabs__link${"app_content".equals(tab) ? " is-active" : ""}">${t.apply("nav_app_content")}</a>
		<a href="/admin/settings?tab=setting_templates"
		   class="settings-hub-tabs__link${"setting_templates".equals(tab) ? " is-active" : ""}">${t.apply("nav_setting_templates")}</a>
		<a href="/admin/settings?tab=system"
		   class="settings-hub-tabs__link${"system".equals(tab) ? " is-active" : ""}">${t.apply("nav_system_settings")}</a>
	</nav>

	@if("app_content".equals(tab))
		@template.admin.settings-content(content = content, activeKey = activeKey,
			csrfParameterName = csrfParameterName, csrfToken = csrfToken,
			canWrite = canWrite, lang = lang, t = t)
	@elseif("setting_templates".equals(tab))
		@template.admin.settings-templates(templates = templates,
			csrfParameterName = csrfParameterName, csrfToken = csrfToken,
			canWrite = canWrite, lang = lang, t = t)
	@else
		@template.admin.settings-system(groupOrder = groupOrder, configGroups = configGroups,
			inputValues = inputValues, csrfParameterName = csrfParameterName,
			csrfToken = csrfToken, canWrite = canWrite, t = t)
	@endif
`)
