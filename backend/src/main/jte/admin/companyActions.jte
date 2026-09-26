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
		<%-- company_helper.php:190-197: Edit right after Details. The controller
		     opens the prefilled form at ?edit=<id>, not legacy's ?action=edit&id=. --%>
		<a href="/admin/companies?edit=${companyId}" class="row-actions__item"
		   role="menuitem">${t.apply("edit")}</a>
		<%-- company_helper.php:191-197: Delete right after Edit. Legacy posts after a
		     browser confirm(); this opens a page that counts what goes with the
		     company and asks for its name first. --%>
		<a href="/admin/companies/${companyId}/delete"
		   class="row-actions__item row-actions__item--danger" role="menuitem">${t.apply("delete")}</a>
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
			      data-confirm="${t.apply("confirm_action")}" data-confirm-tone="danger">
				<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
				<input type="hidden" name="companyId" value="${companyId}">
				<input type="hidden" name="action" value="COMPANY_SUSPEND">
				<button type="submit" class="row-actions__item row-actions__item--danger" role="menuitem">${t.apply("suspend")}</button>
			</form>
		@endif
	@endif
`)
