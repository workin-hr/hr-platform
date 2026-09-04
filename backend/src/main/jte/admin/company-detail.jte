@import com.workin.backend.platformadmin.PlatformAdminCompanyDirectory.CompanyDetail
@import java.util.function.Function
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param boolean actionsEnabled
@param CompanyDetail detail
@param String currentPage
@param String lang
@param boolean isAdmin
@param Function<String, String> t
@param Function<String, Boolean> available
@template.admin.layout(
	title = "Company",
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentAdminPhone = currentAdminPhone,
	currentPage = currentPage,
	lang = lang,
	isAdmin = isAdmin,
	t = t,
	available = available,
	body = @`
		<div class="card">
			<h1>${detail.company().name() == null ? "Unnamed company" : detail.company().name()}</h1>
			<p class="sub">Company #${detail.company().id()}</p>
			<dl class="facts">
				<dt>Status</dt><dd>${detail.company().status()}</dd>
				@if(detail.rejectionReason() != null && !detail.rejectionReason().isBlank())
					<dt>Rejected because</dt><dd>${detail.rejectionReason()}</dd>
				@endif
				<dt>Pending requests</dt><dd>${detail.pendingRequests()}</dd>
				<dt>Pending advances</dt><dd>${detail.pendingAdvances()}</dd>
			</dl>
			<p class="notice">
				Outstanding work is shown because suspending a company with pending
				requests or advances leaves that work stranded.
			</p>
			@if(actionsEnabled)
				<form method="post" action="/admin/companies/confirm">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="companyId" value="${detail.company().id()}">
					@if("pending".equals(detail.company().status()))
						<button type="submit" name="action" value="COMPANY_APPROVE" class="primary">Approve</button>
						<button type="submit" name="action" value="COMPANY_REJECT" class="linkish danger">Reject</button>
					@elseif("suspended".equals(detail.company().status()))
						<button type="submit" name="action" value="COMPANY_RESTORE" class="primary">Restore</button>
					@elseif("rejected".equals(detail.company().status()))
						<button type="submit" name="action" value="COMPANY_APPROVE" class="primary">Approve</button>
					@else
						<button type="submit" name="action" value="COMPANY_SUSPEND" class="linkish danger">Suspend</button>
					@endif
				</form>
			@endif
			<p class="notice"><a href="/admin/companies">Back to companies</a></p>
		</div>
	`)
