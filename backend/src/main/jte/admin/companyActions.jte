@import java.util.function.Function
@param long companyId
@param String status
@param boolean actionsEnabled
@param String csrfParameterName
@param String csrfToken
@param Function<String, String> t
<%-- One cell for both of this page's tables (the MySQL directory and the
     narrow PostgreSQL list), because the actions do not differ between them.

     Legacy posts approve, reject and delete straight from this menu. Here each
     one goes to /admin/companies/confirm, which collects a TOTP code and mints
     an approval bound to that company before anything is applied -- ADR-0015's
     ceremony, and the one place this page deliberately does not reproduce the
     PHP. --%>
@template.admin.rowActions(rowId = companyId, t = t, items = @`
	<a href="/admin/companies/${companyId}" class="row-actions__item"
	   role="menuitem">${t.apply("details")}</a>
	@if(actionsEnabled)
		<form method="post" action="/admin/companies/confirm" class="row-actions__form" role="none">
			<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
			<input type="hidden" name="companyId" value="${companyId}">
			@if("pending".equals(status))
				<button type="submit" name="action" value="COMPANY_APPROVE"
						class="row-actions__item row-actions__item--success" role="menuitem">${t.apply("approve")}</button>
				<button type="submit" name="action" value="COMPANY_REJECT"
						class="row-actions__item row-actions__item--danger" role="menuitem">${t.apply("reject")}</button>
			@elseif("suspended".equals(status))
				<button type="submit" name="action" value="COMPANY_RESTORE"
						class="row-actions__item" role="menuitem">${t.apply("restore")}</button>
			@elseif("rejected".equals(status))
				<button type="submit" name="action" value="COMPANY_APPROVE"
						class="row-actions__item" role="menuitem">${t.apply("approve")}</button>
			@else
				<button type="submit" name="action" value="COMPANY_SUSPEND"
						class="row-actions__item row-actions__item--danger" role="menuitem">${t.apply("suspend")}</button>
			@endif
		</form>
	@endif
`)
