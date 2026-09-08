@import java.util.function.Function
@param long companyId
@param String status
@param boolean actionsEnabled
@param String csrfParameterName
@param String csrfToken
@param Function<String, String> t
<%-- One cell for both of this page's tables, because the actions do not
     differ between them. Each is a CSRF-protected POST to /admin/companies/action,
     as every other page's row actions are; rejecting asks for its reason in
     the page's dialog first. --%>
@template.admin.rowActions(rowId = companyId, t = t, items = @`
	<a href="/admin/companies/${companyId}" class="row-actions__item"
	   role="menuitem">${t.apply("details")}</a>
	@if(actionsEnabled)
		@if("pending".equals(status) || "rejected".equals(status))
			<form method="post" action="/admin/companies/action" class="row-actions__form" role="none">
				<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
				<input type="hidden" name="companyId" value="${companyId}">
				<input type="hidden" name="action" value="COMPANY_APPROVE">
				<button type="submit" class="row-actions__item row-actions__item--success" role="menuitem">${t.apply("approve")}</button>
			</form>
		@endif
		@if("pending".equals(status))
			<button type="button" role="menuitem" class="row-actions__item row-actions__item--danger"
					data-dialog="company-reject"
					data-dialog-companyId="${companyId}"
					data-dialog-subject="#${companyId}">${t.apply("reject")}</button>
		@endif
		@if("suspended".equals(status))
			<form method="post" action="/admin/companies/action" class="row-actions__form" role="none">
				<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
				<input type="hidden" name="companyId" value="${companyId}">
				<input type="hidden" name="action" value="COMPANY_RESTORE">
				<button type="submit" class="row-actions__item" role="menuitem">${t.apply("restore")}</button>
			</form>
		@endif
		@if("active".equals(status))
			<form method="post" action="/admin/companies/action" class="row-actions__form" role="none"
			      onsubmit="return confirm('${t.apply("confirm_action")}')">
				<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
				<input type="hidden" name="companyId" value="${companyId}">
				<input type="hidden" name="action" value="COMPANY_SUSPEND">
				<button type="submit" class="row-actions__item row-actions__item--danger" role="menuitem">${t.apply("suspend")}</button>
			</form>
		@endif
	@endif
`)
