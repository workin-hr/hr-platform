@import com.workin.backend.platformadmin.PlatformAdminCompanyDirectory.CompanyDetail
@import com.workin.backend.platformadmin.hr.EmployeeDisplay
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
@param String flash = null
@param String flashType = "success"
@param Function<String, Boolean> available
<%-- dashboard/pages/companies/detail.php, reached in legacy as
     company_detail.php through a rewrite. --%>
<%-- detail.php renders inside pages/companies, whose page.php:139 names
     these two. The port named none, so this page had the shared sheets and
     nothing else. --%>
!{long id = detail.company().id();}
!{String name = detail.company().name() == null ? "" : detail.company().name();}
@template.admin.layout(
	title = t.apply("company") + " — " + name,
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
		<%-- detail.php writes its layout in style attributes. The company-detail-*
		     classes in admin-extra.css are those attributes, value for value. --%>
		<div class="company-detail-bar">
			<a href="/admin/companies" class="btn btn-outline btn-sm">← ${t.apply("back")}</a>
			<a href="/admin/branches?company_id=${id}" class="btn btn-blue btn-sm">${t.apply("nav_branches")}</a>
			<a href="/admin/employees?company_id=${id}" class="btn btn-green btn-sm">${t.apply("employees")}</a>
			<a href="/admin/payroll?company_id=${id}" class="btn btn-yellow btn-sm">${t.apply("nav_payroll")}</a>
		</div>

		<div class="company-detail-card">
			<%-- company_logo_src() falls back to a ui-avatars.com image. The fallback is
			     drawn locally instead, as the list draws its own (img-fallback.js). It is
			     also what a stored value StoredUrl refuses draws, rather than the
			     card loading it; a value naming https is loaded, as legacy loads
			     one, and legacy reaches its own host check first. --%>
			@if(detail.profile().hasLogo())
				<img src="${detail.profile().logoSrc()}" alt="" class="company-detail-logo"
				     data-fallback-initials="${EmployeeDisplay.initials(detail.avatarName())}"
				     data-fallback-class="company-detail-avatar">
			@else
				<span class="company-detail-avatar" aria-hidden="true">${EmployeeDisplay.initials(detail.avatarName())}</span>
			@endif
			<div>
				<div class="company-detail-name">${name}
					@template.admin.statusBadge(status = detail.company().status(), t = t)</div>
				<div class="company-detail-fields">
					<span>&#9632; ${t.apply("company_phone")}: <strong>${detail.profile().phone()}</strong></span>
					<span>&#9632; ${t.apply("company_email")}: <strong>${detail.profile().emailLabel()}</strong></span>
					<span>&#9632; OTP: <strong>@template.admin.statusBadge(status = detail.profile().otpVerified() ? "1" : "0", t = t)</strong></span>
					<span>&#9632; ${t.apply("reg_date")}: <strong>${detail.profile().registeredOn()}</strong></span>
				</div>
				@if(detail.profile().commercialRegHref() != null)
					<a href="${detail.profile().commercialRegHref()}" target="_blank" rel="noopener"
					   class="btn btn-outline btn-sm company-detail-reg">${t.apply("commercial_reg")}</a>
				@endif
			</div>
		</div>

		<%-- Labelled total_employees, counting the active ones: legacy's label, reproduced. --%>
		<div class="stats-grid company-detail-stats">
			<div class="stat-card green"><div class="stat-num">${detail.activeEmployees()}</div>
				<div class="stat-label">${t.apply("total_employees")}</div></div>
			<div class="stat-card blue"><div class="stat-num">${detail.totalEmployees()}</div>
				<div class="stat-label">${t.apply("employees")}</div></div>
			<div class="stat-card blue"><div class="stat-num">${detail.branches().size()}</div>
				<div class="stat-label">${t.apply("branches")}</div></div>
			<div class="stat-card green"><div class="stat-num">${detail.checkedInToday()}</div>
				<div class="stat-label">${t.apply("checked_in_today")}</div></div>
			<div class="stat-card yellow"><div class="stat-num">${detail.pendingRequests()}</div>
				<div class="stat-label">${t.apply("pending_requests")}</div></div>
			<div class="stat-card yellow"><div class="stat-num">${detail.pendingAdvances()}</div>
				<div class="stat-label">${t.apply("pending_advances")}</div></div>
		</div>

		<%-- The port's own: detail.php has no actions. The reason and the notice sit
		     under the counts they explain, and the buttons above the long tables. --%>
		@if(detail.rejectionReason() != null && !detail.rejectionReason().isBlank())
			<p><span class="bold">${t.apply("rejected_because")}</span>
				<span class="text-muted">${detail.rejectionReason()}</span></p>
		@endif

		<p class="text-muted">${t.apply("outstanding_work_notice")}</p>

		@if(actionsEnabled)
			<div class="section-head">
				<form method="post" action="/admin/companies/action">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="companyId" value="${id}">
					@if("pending".equals(detail.company().status()))
						<button type="submit" name="action" value="COMPANY_APPROVE"
								class="btn btn-green btn-sm">${t.apply("approve")}</button>
						<%-- A rejection must say why; the dialog below asks. --%>
						<button type="button" class="btn btn-red btn-sm" data-dialog="company-reject"
								data-dialog-companyId="${id}"
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
						submitVariant = "red", cancelLabel = t.apply("cancel"),
						fields = @`
							<input type="hidden" name="companyId" data-dialog-field="companyId">
							<div class="form-row">
								<label for="company_reject_reason">${t.apply("rejection_reason")}</label>
								<textarea id="company_reject_reason" name="reason" rows="3" required></textarea>
							</div>
						`)
			@endif
		@endif

		<div class="company-detail-tables">
			<div>
				<div class="section-head"><h2>${t.apply("branches")} (${detail.branches().size()})</h2>
					<a href="/admin/branches?company_id=${id}" class="btn btn-outline btn-sm">${t.apply("edit")}</a></div>
				<div class="table-wrap"><table class="tbl">
					<thead><tr><th>${t.apply("branch")}</th><th>${t.apply("employees")}</th></tr></thead>
					<tbody>
						@for(CompanyDetail.Branch branch : detail.branches())
							<tr><td>${branch.name()}</td><td>${branch.activeEmployees()}</td></tr>
						@endfor
					</tbody>
				</table></div>
			</div>
			<div>
				<%-- "HR / " is legacy's literal, in both languages. --%>
				<div class="section-head"><h2>HR / ${t.apply("manager")}</h2></div>
				<div class="table-wrap"><table class="tbl">
					<thead><tr><th>${t.apply("company_phone")}</th><th>${t.apply("role")}</th></tr></thead>
					<tbody>
						@for(CompanyDetail.StaffUser user : detail.staff())
							<tr><td>${user.phone()}</td>
								<td>@template.admin.statusBadge(status = user.role(), t = t)</td></tr>
						@endfor
					</tbody>
				</table></div>
			</div>
			<div class="company-detail-wide">
				<div class="section-head"><h2>${t.apply("employees")} (${detail.totalEmployees()})</h2>
					<a href="/admin/employees?company_id=${id}" class="btn btn-outline btn-sm">${t.apply("all")}</a></div>
				<div class="table-wrap"><table class="tbl">
					<thead><tr><th>#</th><th>${t.apply("emp_code")}</th><th>${t.apply("employee_name")}</th>
						<th>${t.apply("company_phone")}</th><th>${t.apply("branch")}</th>
						<th>${t.apply("hire_date")}</th><th>${t.apply("status")}</th>
						<th>${t.apply("details")}</th></tr></thead>
					<tbody>
						@for(int index = 0; index < detail.employees().size(); index++)
							!{CompanyDetail.Employee row = detail.employees().get(index);}
							<tr>
								<td>${index + 1}</td>
								<td class="text-muted">${row.code()}</td>
								<td class="bold">${row.nameLabel()}</td>
								<td>${row.phone()}</td>
								<td>${row.branchLabel()}</td>
								<td>${row.hireDateLabel()}</td>
								<td>@template.admin.statusBadge(status = row.active() ? "1" : "0", t = t)</td>
								<td><a href="/admin/employee_detail?id=${row.id()}" class="btn btn-blue btn-sm">${t.apply("details")}</a></td>
							</tr>
						@endfor
						@if(detail.moreEmployees() > 0)
							<%-- The "و" is legacy's literal, in both languages. --%>
							<tr><td colspan="8" class="company-detail-more">و ${detail.moreEmployees()} ${t.apply("employee")} — <a href="/admin/employees?company_id=${id}">${t.apply("all")}</a></td></tr>
						@endif
					</tbody>
				</table></div>
			</div>
		</div>
	`)
