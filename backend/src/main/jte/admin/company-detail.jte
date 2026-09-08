@import com.workin.backend.platformadmin.PlatformAdminCompanyDirectory.CompanyDetail
@import java.util.function.Function
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param boolean actionsEnabled
@param CompanyDetail detail
@param String currentPage
@param String lang
@param com.workin.backend.platformadmin.web.DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
@template.admin.layout(
	title = t.apply("company"),
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentAdminPhone = currentAdminPhone,
	currentPage = currentPage,
	lang = lang,
	session = session,
	t = t,
	available = available,
	contentClass = "",
	body = @`
		<div class="card">
			<h1>${detail.company().name() == null ? t.apply("unnamed_company") : detail.company().name()}</h1>
			<p class="sub">${t.apply("company")} #${detail.company().id()}</p>
			<dl class="facts">
				<dt>${t.apply("status")}</dt><dd>${detail.company().status()}</dd>
				@if(detail.rejectionReason() != null && !detail.rejectionReason().isBlank())
					<dt>${t.apply("rejected_because")}</dt><dd>${detail.rejectionReason()}</dd>
				@endif
				<dt>${t.apply("pending_requests")}</dt><dd>${detail.pendingRequests()}</dd>
				<dt>${t.apply("pending_advances")}</dt><dd>${detail.pendingAdvances()}</dd>
			</dl>
			<p class="notice">${t.apply("outstanding_work_notice")}</p>
			@if(actionsEnabled)
				<form method="post" action="/admin/companies/action">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="companyId" value="${detail.company().id()}">
					@if("pending".equals(detail.company().status()))
						<button type="submit" name="action" value="COMPANY_APPROVE" class="primary">${t.apply("approve")}</button>
						<%-- A rejection must say why; the dialog below asks. --%>
						<button type="button" class="linkish danger" data-dialog="company-reject"
								data-dialog-companyId="${detail.company().id()}"
								data-dialog-subject="${detail.company().name()}">${t.apply("reject")}</button>
					@elseif("suspended".equals(detail.company().status()))
						<button type="submit" name="action" value="COMPANY_RESTORE" class="primary">${t.apply("restore")}</button>
					@elseif("rejected".equals(detail.company().status()))
						<button type="submit" name="action" value="COMPANY_APPROVE" class="primary">${t.apply("approve")}</button>
					@else
						<button type="submit" name="action" value="COMPANY_SUSPEND" class="linkish danger">${t.apply("suspend")}</button>
					@endif
				</form>
				@if("pending".equals(detail.company().status()))
					@template.admin.rowDialog(id = "company-reject", action = "COMPANY_REJECT",
							formAction = "/admin/companies/action",
							title = t.apply("reject"), csrfParameterName = csrfParameterName,
							csrfToken = csrfToken, t = t, submitLabel = t.apply("reject"),
							submitVariant = "danger",
							fields = @`
								<input type="hidden" name="companyId" data-dialog-field="companyId">
								<label>${t.apply("rejection_reason")}
									<textarea name="reason" rows="3" required></textarea></label>
							`)
				@endif
			@endif
			<p class="notice"><a href="/admin/companies">${t.apply("back_to_companies")}</a></p>
		</div>
	`)
