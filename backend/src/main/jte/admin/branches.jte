@import java.util.function.Function
@import com.workin.backend.platformadmin.org.ActiveCompanies
@import com.workin.backend.platformadmin.org.Branch
@import com.workin.backend.platformadmin.web.DashboardListFilters
@import com.workin.backend.platformadmin.web.DashboardPage
@import com.workin.backend.platformadmin.web.DashboardSession
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param DashboardPage<Branch> result
@param DashboardListFilters filters
@param java.util.function.Supplier<java.util.List<com.workin.backend.platformadmin.org.ActiveCompanies.CompanyOption>> companyFilterOptions
@param java.util.List<ActiveCompanies.CompanyOption> companyOptions
@param boolean showCompanyColumn
@param boolean canManage
@param boolean actionsEnabled
@param Branch editRow = null
@param Branch qrRow = null
@param boolean addOpen = false
@param java.time.LocalDateTime now
@param String errorKey = null
@param String currentPage
@param String lang
@param DashboardSession session
@param Function<String, String> t
@param String flash = null
@param String flashType = "success"
@param Function<String, Boolean> available
!{boolean canWrite = canManage && actionsEnabled;}
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
	flash = flash,
	flashType = flashType,
	available = available,
	pageStyles = java.util.List.of("hr-pages.css", "org-form.css"),
	body = @`
		@if(errorKey != null)
			<div class="flash flash-error">${t.apply(errorKey)}</div>
		@endif
		@if(canManage && !actionsEnabled)
			<div class="flash flash-warning">${t.apply("admin_actions_disabled")}</div>
		@endif

		<div class="toolbar page-toolbar page-toolbar-filters">
			<form method="GET" class="toolbar-form toolbar-form--labeled">
				<%-- The administrator's company filter. A scoped session has no
				     such choice, so the field is not rendered for one. --%>
				@if(!session.isScopedToOneCompany())
					@template.admin.companyFilterField(companyId = filters.companyId(), companies = companyFilterOptions, t = t)
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
				session = session, filters = filters, canWrite = canWrite,
				companyOptions = companyOptions, t = t)
		@endif

		@if(qrRow != null)
			<%-- _branch_qr_modal.php: a modal-bg of its own, not a data-table-card
			     in the page flow -- the port had drawn it inline, so it never got
			     org-form.css's modal chrome, and its status/image/meta ran in the
			     wrong order with no id="br_qr_expires" for its label. --%>
			<div class="modal-bg open">
				<div class="modal modal--org-form modal--branch-qr">
					<h2>${t.apply("branch_generate_qr")}</h2>
					<p class="branch-qr-branch-name">${qrRow.name()}</p>

					@if(qrRow.qrActive(now))
						<div class="branch-qr-preview">
							<p class="branch-qr-status branch-qr-status--active">${t.apply("branch_qr_active")}</p>
							<%-- Legacy renders the code through api.qrserver.com. Reproduced,
							     and worth stating: this sends the branch's check-in code to a
							     third party on every render. --%>
							<img src="${Branch.qrImageUrl(qrRow.qrCode())}" width="220" height="220"
							     alt="QR" class="branch-qr-image">
							<p class="branch-qr-meta">
								<span>${t.apply("branch_qr_expires_at")}:</span>
								<strong dir="ltr">${qrRow.expiresDisplay()}</strong>
							</p>
						</div>
					@elseif(qrRow.qrCode() != null && !qrRow.qrCode().isEmpty())
						<p class="branch-qr-status branch-qr-status--expired">${t.apply("branch_qr_expired")}</p>
					@else
						<p class="branch-qr-status">${t.apply("branch_qr_none")}</p>
					@endif

					<%-- The window is the whole viewport and legacy gives it no close button,
					     so its Cancel is the only way out that announces itself. It stays
					     outside the actions gate for that reason, as branch-form.jte:87-92
					     keeps its own: with the switch off -- which is the shipped default --
					     gating the footer left a full-screen overlay a reader could dismiss
					     only by guessing at Escape or the backdrop. --%>
					<form method="POST" class="branch-qr-form">
						<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
						<input type="hidden" name="action" value="generate_qr">
						<input type="hidden" name="id" value="${qrRow.id()}">
						<input type="hidden" name="company_id" value="${qrRow.companyId()}">
						<div class="form-row">
							<label for="br_qr_expires">${t.apply("branch_qr_expires_at")}</label>
							<input type="datetime-local" id="br_qr_expires" name="expires_at" required
							       value="${qrRow.qrExpiresInputValue(now)}">
						</div>
						<div class="form-footer">
							<a href="/admin/branches" class="btn btn-gray">${t.apply("cancel")}</a>
							@if(canWrite)
								<button type="submit" class="btn btn-yellow">${t.apply(qrRow.qrActive(now) ? "branch_qr_regenerate" : "branch_qr_create")}</button>
							@endif
						</div>
					</form>
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
			<div class="table-wrap">
			<table class="tbl">
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
						@template.admin.actionsHeader(t = t)
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
								@template.admin.statusBadge(status = row.active() ? "active" : "suspended", t = t)
							</td>
							<td>${row.createdDate()}</td>
							<td class="col-actions">
								@if(canManage)
									@template.admin.rowActions(rowId = row.id(), t = t, items = @`
										<a href="/admin/branches?action=edit&id=${row.id()}"
										   role="menuitem" class="row-actions__item">${t.apply("edit")}</a>
										@if(canWrite)
											<form method="POST" class="row-actions__form" role="none"
											      onsubmit="return confirm('${t.apply("confirm_delete")}')">
												<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
												<input type="hidden" name="action" value="delete">
												<input type="hidden" name="id" value="${row.id()}">
												<input type="hidden" name="company_id" value="${row.companyId()}">
												<button type="submit" role="menuitem" class="row-actions__item row-actions__item--danger">${t.apply("delete")}</button>
											</form>
										@endif
										<a href="/admin/branches?action=qr&id=${row.id()}"
										   role="menuitem" class="row-actions__item">${t.apply("branch_generate_qr")}</a>
									`)
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
		</div>
		@template.admin.pager(basePath = "/admin/branches", result = result,
				carry = filters.asQueryParameters(), t = t)
	`)
