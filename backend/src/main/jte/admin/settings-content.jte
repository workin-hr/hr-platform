@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.settings.AppContentEntry
@param List<AppContentEntry> content
@param String activeKey
@param String csrfParameterName
@param String csrfToken
@param boolean canWrite
@param String lang
@param Function<String, String> t
<%-- dashboard/pages/settings/_tab_app_content.php. The three documents are
     always all present: the store seeds every key before overlaying rows, so a
     document never saved renders blank rather than missing. --%>
<div class="app-content-page">

	<div class="app-content-tabs" role="tablist">
		@for(AppContentEntry entry : content)
			<button type="button"
			        class="app-content-tab${entry.key().equals(activeKey) ? " is-active" : ""}"
			        role="tab"
			        data-app-content-tab="${entry.key()}"
			        aria-selected="${entry.key().equals(activeKey)}">
				<span class="app-content-tab__icon">${"shield".equals(entry.icon()) ? "🛡"
					: "guide".equals(entry.icon()) ? "📖"
					: "document".equals(entry.icon()) ? "📄" : "📝"}</span>
				<span>${"ar".equals(lang) ? entry.labelAr() : entry.labelEn()}</span>
			</button>
		@endfor
	</div>

	<div class="app-content-panel-wrap">
		@for(AppContentEntry entry : content)
			<div class="app-content-panel${entry.key().equals(activeKey) ? " is-active" : ""}"
			     data-app-content-panel="${entry.key()}" role="tabpanel">
				<form method="POST" action="/admin/settings">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="save_content">
					<input type="hidden" name="content_key" value="${entry.key()}">

					<div class="app-content-panel__head">
						<div>
							<h3 class="app-content-panel__title">${"ar".equals(lang) ? entry.labelAr() : entry.labelEn()}</h3>
						</div>
						@if(entry.saved())
							<span class="app-content-panel__meta">
								${t.apply("app_content_last_updated")}:
								${entry.updatedAt().toString().replace("T", " ").substring(0, 16)}
							</span>
						@endif
					</div>

					<div class="app-content-lang-grid">
						<div class="app-content-lang-card">
							<div class="app-content-lang-card__head app-content-lang-card__head--ar">العربية</div>
							<textarea name="content_value_ar" dir="rtl" placeholder="النص بالعربية…">${entry.valueAr()}</textarea>
						</div>
						<div class="app-content-lang-card">
							<div class="app-content-lang-card__head">English</div>
							<textarea name="content_value_en" dir="ltr" placeholder="English text…">${entry.valueEn()}</textarea>
						</div>
					</div>

					<div class="app-content-panel__footer">
						<button type="submit" class="btn btn-blue" disabled="${!canWrite}">${t.apply("app_content_save_section")}</button>
					</div>
				</form>
			</div>
		@endfor
	</div>
</div>
