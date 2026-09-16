@import com.workin.backend.platformadmin.PlatformAdminCompanyDirectory.DeletionTarget
@import com.workin.backend.platformadmin.web.DashboardSession
@import com.workin.legacy.profile.LegacyCompanyDelete.ClearedTable
@import java.util.List
@import java.util.function.Function
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param boolean actionsEnabled
@param DeletionTarget target
@param List<ClearedTable> cleared
@param long clearedTotal
@param String errorKey = null
@param String currentPage
@param String lang
@param DashboardSession session
@param Function<String, String> t
@param String flash = null
@param String flashType = "success"
@param Function<String, Boolean> available
<%-- Legacy has no page here: pages/companies/page.php deletes on a browser
     confirm(). This one counts what goes with the company and asks for its
     name typed back, in company-detail.jte's vocabulary rather than a new one. --%>
@template.admin.layout(
	title = t.apply("company_delete_title"),
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentAdminPhone = currentAdminPhone,
	currentPage = currentPage,
	lang = lang,
	session = session,
	t = t,
	flash = flash,
	flashType = flashType,
	available = available,
	pageStyles = java.util.List.of("hr-pages.css", "company-add.css"),
	contentClass = "",
	body = @`
		<div class="section-head">
			<h2>${t.apply("company_delete_title")}: ${target.company().name() == null ? t.apply("unnamed_company") : target.company().name()}</h2>
			<a href="/admin/companies/${target.company().id()}" class="btn btn-outline btn-sm">${t.apply("details")}</a>
		</div>

		@if(errorKey != null)
			<div class="flash flash-error">${t.apply(errorKey)}</div>
		@endif
		<div class="flash flash-warning">${t.apply("company_delete_warning")}</div>

		<div class="stats-grid">
			<div class="stat-card blue">
				<div class="stat-num">#${target.company().id()}</div>
				<div class="stat-label">${t.apply("company")}</div>
			</div>
			<div class="stat-card red">
				<div class="stat-num">${clearedTotal}</div>
				<div class="stat-label">${t.apply("company_delete_total")}</div>
			</div>
			<%-- One card per table the cascade deletes from. The key is built from the
			     table name, so CompanyDeleteTableLabelsTest, not the template key gate,
			     holds both catalogues to the cascade's list. --%>
			@for(ClearedTable table : cleared)
				<div class="stat-card gray">
					<div class="stat-num">${table.rows()}</div>
					<div class="stat-label">${t.apply("company_delete_table_" + table.table())}</div>
				</div>
			@endfor
		</div>
		@if(cleared.isEmpty())
			<p class="text-muted">${t.apply("company_delete_nothing_related")}</p>
		@endif

		@if(actionsEnabled)
			<form method="post" action="/admin/companies/${target.company().id()}/delete">
				<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
				<div class="form-row">
					<label for="company-delete-confirmation">${t.apply("company_delete_type_to_confirm")}
						<code dir="auto">${target.confirmationText()}</code></label>
					<input type="text" id="company-delete-confirmation" name="confirmation"
					       required autocomplete="off" dir="auto">
				</div>
				<button type="submit" class="btn btn-red btn-sm">${t.apply("delete")}</button>
				<a href="/admin/companies" class="btn btn-outline btn-sm">${t.apply("back_to_companies")}</a>
			</form>
		@else
			<div class="flash flash-warning">${t.apply("admin_actions_disabled")}</div>
		@endif
	`)
