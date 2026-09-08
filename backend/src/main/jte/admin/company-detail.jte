@import com.workin.backend.platformadmin.PlatformAdminCompanyDirectory.CompanyDetail
@import com.workin.backend.platformadmin.web.DashboardSession
@import java.util.function.Function
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param boolean actionsEnabled
@param CompanyDetail detail
@param String currentPage
@param String lang
@param DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
<%-- detail.php renders inside pages/companies, whose page.php:139 names
     these two. The port named none, so this page had the shared sheets and
     nothing else. --%>
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
	pageStyles = java.util.List.of("hr-pages.css", "company-add.css"),
	contentClass = "",
	body = @`
		<%-- detail.php's own vocabulary: section-head, stats-grid, stat-card and
		     btn. The port invented card/sub/facts/notice/linkish/primary here,
		     none of which any stylesheet defines -- in this repository or in
		     legacy -- so the page rendered as unstyled markup. --%>
		<div class="section-head">
			<h2>${detail.company().name() == null ? t.apply("unnamed_company") : detail.company().name()}</h2>
			<a href="/admin/companies" class="btn btn-outline btn-sm">${t.apply("back_to_companies")}</a>
		</div>

		<div class="stats-grid">
			<div class="stat-card blue">
				<div class="stat-num">#${detail.company().id()}</div>
				<div class="stat-label">${t.apply("company")}</div>
			</div>
			<%-- The same badge the list draws, and for the same reason: the column
			     holds "active"/"pending"/"suspended"/"rejected", which is a stored
			     value and not a word to show anyone. --%>
			<div class="stat-card green">
				<div class="stat-num">
					<span class="badge ${"active".equals(detail.company().status()) ? "badge-green"
							: "pending".equals(detail.company().status()) ? "badge-yellow"
							: "rejected".equals(detail.company().status()) ? "badge-red" : "badge-gray"}">
						${t.apply("status_" + detail.company().status())}</span>
				</div>
				<div class="stat-label">${t.apply("status")}</div>
			</div>
			<div class="stat-card yellow">
				<div class="stat-num">${detail.pendingRequests()}</div>
				<div class="stat-label">${t.apply("pending_requests")}</div>
			</div>
			<div class="stat-card yellow">
				<div class="stat-num">${detail.pendingAdvances()}</div>
				<div class="stat-label">${t.apply("pending_advances")}</div>
			</div>
		</div>

		@if(detail.rejectionReason() != null && !detail.rejectionReason().isBlank())
			<p><span class="bold">${t.apply("rejected_because")}</span>
				<span class="text-muted">${detail.rejectionReason()}</span></p>
		@endif

		<p class="text-muted">${t.apply("outstanding_work_notice")}</p>

		@if(actionsEnabled)
			<div class="section-head">
				<form method="post" action="/admin/companies/action">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="companyId" value="${detail.company().id()}">
					@if("pending".equals(detail.company().status()))
						<button type="submit" name="action" value="COMPANY_APPROVE"
								class="btn btn-green btn-sm">${t.apply("approve")}</button>
						<%-- A rejection must say why; the dialog below asks. --%>
						<button type="button" class="btn btn-red btn-sm" data-dialog="company-reject"
								data-dialog-companyId="${detail.company().id()}"
								data-dialog-subject="${detail.company().name()}">${t.apply("reject")}</button>
					@elseif("suspended".equals(detail.company().status()))
						<button type="submit" name="action" value="COMPANY_RESTORE"
								class="btn btn-blue btn-sm">${t.apply("restore")}</button>
					@elseif("rejected".equals(detail.company().status()))
						<button type="submit" name="action" value="COMPANY_APPROVE"
								class="btn btn-green btn-sm">${t.apply("approve")}</button>
					@else
						<button type="submit" name="action" value="COMPANY_SUSPEND"
								class="btn btn-yellow btn-sm">${t.apply("suspend")}</button>
					@endif
				</form>
			</div>
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
	`)
