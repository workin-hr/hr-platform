@import java.util.function.Function
@import com.workin.backend.platformadmin.org.Branch
@import com.workin.backend.platformadmin.web.DashboardListFilters
@import com.workin.backend.platformadmin.web.DashboardPage
@import com.workin.backend.platformadmin.web.DashboardSession
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param DashboardPage<Branch> result
@param DashboardListFilters filters
@param boolean showCompanyColumn
@param boolean canManage
@param boolean actionsEnabled
@param boolean factorBound
@param Branch editRow = null
@param Branch qrRow = null
@param boolean addOpen = false
@param java.time.LocalDateTime now
@param String errorKey = null
@param String currentPage
@param String lang
@param DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
!{boolean canWrite = canManage && actionsEnabled && factorBound;}
!{int colCount = showCompanyColumn ? 11 : 10;}
<%-- dashboard/pages/branches/page.php. Class names are the dashboard's own
     (hr-pages.css and org-form.css, both copied verbatim); a template that
     invents one gets no styling, which keeps the copy honest. --%>
@template.admin.layout(
	title = t.apply("branches"),
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentAdminPhone = currentAdminPhone,
	currentPage = currentPage,
	lang = lang,
	session = session,
	t = t,
	available = available,
	pageStyles = java.util.List.of("hr-pages.css", "org-form.css"),
	body = @`
		@if(errorKey != null)
			<div class="flash flash-error">${t.apply(errorKey)}</div>
		@endif
		@if(canManage && !actionsEnabled)
			<div class="flash flash-warning">${t.apply("admin_actions_disabled")}</div>
		@elseif(canManage && !factorBound)
			<div class="flash flash-warning">${t.apply("mfa_required_for_actions")}</div>
		@endif

		<div class="toolbar page-toolbar page-toolbar-filters">
			<form method="GET" class="toolbar-form toolbar-form--labeled">
				<%-- The administrator's company filter. A scoped session has no
				     such choice, so the field is not rendered for one. --%>
				@if(!session.isScopedToOneCompany())
					<div class="filter-field">
						<label class="filter-field__label" for="br_company">${t.apply("company")}</label>
						<input type="number" id="br_company" name="company_id" min="0"
						       placeholder="${t.apply("all")}"
						       value="${filters.companyId() > 0 ? String.valueOf(filters.companyId()) : ""}">
					</div>
				@endif
				<div class="filter-field">
					<label class="filter-field__label" for="br_search">${t.apply("branch")}</label>
					<input type="search" id="br_search" name="search"
					       placeholder="${t.apply("search")}..." value="${filters.search()}">
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="br_status">${t.apply("status")}</label>
					<select id="br_status" name="filter">
						<option value="all" selected="${"all".equals(filters.status())}">${t.apply("filter_all")}</option>
						<option value="active" selected="${"active".equals(filters.status())}">${t.apply("status_active")}</option>
						<option value="inactive" selected="${"inactive".equals(filters.status())}">${t.apply("status_inactive")}</option>
					</select>
				</div>
				<button type="submit" class="btn btn-blue">${t.apply("search")}</button>
			</form>
		</div>

		@if(addOpen || editRow != null)
			@template.admin.branch-form(
				branch = editRow, csrfParameterName = csrfParameterName, csrfToken = csrfToken,
				session = session, filters = filters, canWrite = canWrite, t = t)
		@endif

		@if(qrRow != null)
			<div class="data-table-card">
				<div class="data-table-head">
					<h2 class="data-table-title">${t.apply("branch_generate_qr")} — ${qrRow.name()}</h2>
				</div>
				<div class="account-card">
					@if(qrRow.qrActive(now))
						<p class="badge badge-green">${t.apply("branch_qr_active")}</p>
						<p>${t.apply("branch_qr_expires_at")}: ${qrRow.expiresDisplay()}</p>
						<%-- Legacy renders the code through api.qrserver.com. Reproduced,
						     and worth stating: this sends the branch's check-in code to a
						     third party on every render. --%>
						<img src="${Branch.qrImageUrl(qrRow.qrCode())}" alt="${t.apply("branch_generate_qr")}"
						     width="220" height="220">
					@elseif(qrRow.qrCode() != null && !qrRow.qrCode().isEmpty())
						<p class="text-muted">${t.apply("branch_qr_expired")}</p>
					@else
						<p class="text-muted">${t.apply("branch_qr_none")}</p>
					@endif
					@if(canWrite)
						<form method="POST" class="account-form">
							<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
							<input type="hidden" name="action" value="generate_qr">
							<input type="hidden" name="id" value="${qrRow.id()}">
							<input type="hidden" name="company_id" value="${qrRow.companyId()}">
							<div class="form-row">
								<label for="expires_at">${t.apply("branch_qr_expires_at")}</label>
								<input type="datetime-local" id="expires_at" name="expires_at" required>
							</div>
							<div class="account-form__footer">
								<a href="/admin/branches" class="btn btn-gray">${t.apply("cancel")}</a>
								<button type="submit" class="btn btn-yellow">${t.apply(qrRow.qrActive(now) ? "branch_qr_regenerate" : "branch_qr_create")}</button>
							</div>
						</form>
					@endif
				</div>
			</div>
		@endif

		<div class="data-table-card">
			<div class="data-table-head">
				<h2 class="data-table-title">${t.apply("branches")} (${result.total()})</h2>
				@if(canManage)
					<a href="/admin/branches?action=add" class="btn btn-blue">+ ${t.apply("add_branch")}</a>
				@endif
			</div>
			<div class="data-table-wrap">
			<table class="data-table">
				<thead>
					<tr>
						<th>#</th>
						@if(showCompanyColumn)<th>${t.apply("company")}</th>@endif
						<th>${t.apply("branch")}</th>
						<th>${t.apply("address")}</th>
						<th>${t.apply("branch_open_map")}</th>
						<th class="col-center">${t.apply("branch_attendance_radius")}</th>
						<th>${t.apply("employees")}</th>
						<th>${t.apply("departments")}</th>
						<th>${t.apply("status")}</th>
						<th>${t.apply("reg_date")}</th>
						<th>${t.apply("actions")}</th>
					</tr>
				</thead>
				<tbody>
					@for(int index = 0; index < result.data().size(); index++)
						!{Branch row = result.data().get(index);}
						<tr>
							<td>${result.from() + index}</td>
							@if(showCompanyColumn)
								<td class="text-muted">${row.companyName() == null ? "—" : row.companyName()}</td>
							@endif
							<td class="bold">${row.name()}</td>
							<td class="text-muted">${row.address() == null ? "—" : row.address()}</td>
							<td>
								@if(row.hasCoordinates())
									<a href="${row.mapUrl()}" target="_blank" rel="noopener noreferrer"
									   class="btn btn-outline btn-sm branch-map-link">${t.apply("branch_open_map")}</a>
								@else
									<span class="text-muted">${t.apply("branch_no_location")}</span>
								@endif
							</td>
							<td class="col-center"><span class="badge badge-gray">${row.radiusMeters()}</span></td>
							<td><span class="badge badge-blue">${row.employeeCount()}</span></td>
							<td><span class="badge badge-gray">${row.departmentCount()}</span></td>
							<td>
								<span class="badge ${row.active() ? "badge-green" : "badge-red"}">
									${t.apply(row.active() ? "status_active" : "status_suspended")}</span>
							</td>
							<td>${row.createdDate()}</td>
							<td class="col-actions">
								@if(canManage)
									<a href="/admin/branches?action=edit&id=${row.id()}"
									   class="btn btn-outline btn-sm">${t.apply("edit")}</a>
									<a href="/admin/branches?action=qr&id=${row.id()}"
									   class="btn btn-outline btn-sm">${t.apply("branch_generate_qr")}</a>
									@if(canWrite)
										<form method="POST" class="inline-form"
										      onsubmit="return confirm('${t.apply("confirm_delete")}')">
											<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
											<input type="hidden" name="action" value="delete">
											<input type="hidden" name="id" value="${row.id()}">
											<input type="hidden" name="company_id" value="${row.companyId()}">
											<button type="submit" class="btn btn-danger btn-sm">${t.apply("delete")}</button>
										</form>
									@endif
								@else
									<span class="text-muted">—</span>
								@endif
							</td>
						</tr>
					@endfor
					@if(result.isEmpty())
						<tr><td colspan="${colCount}" class="data-table-empty">${t.apply("no_data")}</td></tr>
					@endif
				</tbody>
			</table>
			</div>
			@if(result.pages() > 1)
				<div class="pager">
					@for(int page = 1; page <= result.pages(); page++)
						<a href="/admin/branches?page=${page}${filters.asQueryTail()}"
						   class="pager__link${page == result.page() ? " is-current" : ""}">${page}</a>
					@endfor
				</div>
			@endif
		</div>
	`)
