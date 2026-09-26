@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.hr.WorkforcePlan
@import com.workin.backend.platformadmin.org.ActiveCompanies
@import com.workin.backend.platformadmin.web.DashboardListFilters
@import com.workin.backend.platformadmin.web.DashboardPage
@import com.workin.backend.platformadmin.web.DashboardSession
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param DashboardPage<WorkforcePlan> result
@param DashboardListFilters filters
@param java.util.function.Supplier<java.util.List<com.workin.backend.platformadmin.org.ActiveCompanies.CompanyOption>> companyFilterOptions
@param List<ActiveCompanies.CompanyOption> companyOptions = List.of()
@param String branchesByCompany = "{}"
@param String departmentsByBranch = "{}"
@param String jobTitlesByDepartment = "{}"
@param String jobTitlesByCompany = "{}"
@param boolean canManage
@param boolean actionsEnabled
@param WorkforcePlan editRow = null
@param boolean addOpen = false
@param String errorKey = null
@param String currentPage
@param String lang
@param DashboardSession session
@param Function<String, String> t
@param String flash = null
@param String flashType = "success"
@param Function<String, Boolean> available
!{boolean canWrite = canManage && actionsEnabled;}
<%-- dashboard/pages/workforce_planning/page.php. Seven columns, and no
     company column even for an unfiltered administrator -- legacy's header
     list does not have one. --%>
@template.admin.layout(
	title = t.apply("nav_workforce_planning"),
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
	pageScripts = java.util.List.of("workforce-form.js"),
	body = @`
		@if(errorKey != null)
			<div class="flash flash-error">${t.apply(errorKey)}</div>
		@endif
		@if(canManage && !actionsEnabled)
			<div class="flash flash-warning">${t.apply("admin_actions_disabled")}</div>
		@endif

		<div class="toolbar page-toolbar page-toolbar-filters">
			<form method="GET" class="toolbar-form toolbar-form--labeled">
				@if(!session.isScopedToOneCompany())
					@template.admin.companyFilterField(companyId = filters.companyId(), companies = companyFilterOptions, t = t)
				@endif
				<div class="filter-field">
					<%-- The search matches the job title only, not the branch or
					     the department, though both are shown. --%>
					<label class="filter-field__label" for="wp_search">${t.apply("search")}</label>
					<input type="search" id="wp_search" name="search"
					       placeholder="${t.apply("workforce_search_placeholder")}"
					       value="${filters.search()}">
				</div>
				<button type="submit" class="btn btn-blue">${t.apply("search")}</button>
			</form>
		</div>

		@if(addOpen || editRow != null)
			<%-- page.php renders this in modal modal--org-form over a
			     modal-bg. The close button legacy also carries is not copied: it
			     is opened and closed by crud.js there, and this form is rendered
			     by the server. workforce-form.js finds the form by #wpModal and
			     data-org-wp-form, and fills the three selects from the maps, which
			     the server leaves empty as legacy's page does. R-051: each map holds
			     only the companies this form may use. --%>
			<div class="modal-bg open" id="wpModal">
				<div class="modal modal--org-form">
					<h2>${editRow == null ? t.apply("add_workforce")
							: t.apply("edit") + " " + t.apply("workforce_item")}</h2>
					<form method="POST" novalidate
					      data-org-wp-form
					      data-branches-by-company="${branchesByCompany}"
					      data-departments-by-branch="${departmentsByBranch}"
					      data-job-titles-by-dept="${jobTitlesByDepartment}"
					      data-job-titles-by-company="${jobTitlesByCompany}"
					      data-placeholder-branch="${t.apply("branch")}..."
					      data-placeholder-job="${t.apply("job_title")}..."
					      data-selected-branch="${editRow == null ? "" : String.valueOf(editRow.branchId())}"
					      data-selected-department="${editRow == null ? "" : String.valueOf(editRow.departmentId())}"
					      data-selected-job-title="${editRow == null ? "" : String.valueOf(editRow.jobTitleId())}">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="${editRow == null ? "add_wp" : "edit_wp"}">
					@if(editRow != null)
						<input type="hidden" name="id" value="${editRow.id()}">
						<%-- The row's company, for the script to read. Not named, so not
						     posted: on an edit the row's own company decides everything,
						     D-176. --%>
						<input type="hidden" data-fixed-company="${editRow.companyId()}" value="${editRow.companyId()}">
					@else
						@template.admin.companyField(session = session, companyId = filters.companyId(),
								companies = companyOptions, inputId = "wp_company_id", cascade = "wp", t = t)
					@endif

					<div class="form-row">
						<label for="wp_branch">${t.apply("branch")} *</label>
						<select name="branch_id" id="wp_branch" data-wp-branch required></select>
					</div>
					<div class="form-row">
						<%-- Optional: 0 is how "no department" is posted, and the
						     column is NOT NULL DEFAULT 0 rather than nullable. --%>
						<label for="wp_department">${t.apply("department")}</label>
						<select name="department_id" id="wp_department" data-wp-department></select>
					</div>
					<div class="form-row">
						<label for="wp_job">${t.apply("job_title")} *</label>
						<select name="job_title_id" id="wp_job" data-wp-job-title required></select>
					</div>
					<div class="form-row">
						<label for="wp_planned">${t.apply("planned_count")} *</label>
						<input type="number" id="wp_planned" min="0" name="planned_count" required
						       value="${editRow == null ? "1" : String.valueOf(editRow.plannedCount())}">
					</div>
					<div class="form-footer">
						<a href="/admin/workforce_planning" class="btn btn-gray">${t.apply("cancel")}</a>
						@if(canWrite)
							<%-- Disabled until workforce-form.js sees a company, a branch,
							     a job title and a count, as legacy's is. --%>
							<button type="submit" class="btn btn-blue" data-wp-submit disabled>${t.apply("save")}</button>
						@endif
					</div>
					</form>
				</div>
			</div>
		@endif

		<div class="data-table-card">
			<div class="data-table-head">
				<h2 class="data-table-title">${t.apply("nav_workforce_planning")} (${result.total()})</h2>
				@if(canManage)
					<a href="/admin/workforce_planning?action=add" class="btn btn-blue">+ ${t.apply("add_workforce")}</a>
				@endif
			</div>
			<div class="table-wrap">
			<table class="tbl">
				<thead>
					<tr>
						<th>#</th>
						<th>${t.apply("branch")}</th>
						<th>${t.apply("department")}</th>
						<th>${t.apply("job_title")}</th>
						<th>${t.apply("planned_count")}</th>
						<th>${t.apply("actual_count")}</th>
						@template.admin.actionsHeader(t = t)
					</tr>
				</thead>
				<tbody>
					@for(int index = 0; index < result.data().size(); index++)
						!{WorkforcePlan row = result.data().get(index);}
						<tr>
							<td>${result.from() + index}</td>
							<td>${row.branchLabel()}</td>
							<td>${row.departmentLabel()}</td>
							<td class="bold">${row.jobTitleLabel()}</td>
							<td>${row.plannedCount()}</td>
							<td class="${row.understaffed() ? "text-red" : "text-green"}">${row.actualCount()}</td>
							<td class="col-actions">
								@if(canManage)
									@template.admin.rowActions(rowId = row.id(), t = t, items = @`
										<a href="/admin/workforce_planning?action=edit&id=${row.id()}"
										   role="menuitem" class="row-actions__item">${t.apply("edit")}</a>
										@if(canWrite)
											<form method="POST" class="row-actions__form" role="none"
											      data-confirm="${t.apply("confirm_delete")}" data-confirm-tone="danger">
												<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
												<input type="hidden" name="action" value="delete_wp">
												<input type="hidden" name="id" value="${row.id()}">
												<button type="submit" role="menuitem" class="row-actions__item row-actions__item--danger">${t.apply("delete")}</button>
											</form>
										@endif
									`)
								@else
									<span class="text-muted">—</span>
								@endif
							</td>
						</tr>
					@endfor
					@if(result.isEmpty())
						<tr><td colspan="7" class="data-table-empty">${t.apply("no_data")}</td></tr>
					@endif
				</tbody>
			</table>
			</div>
		</div>
		@template.admin.pager(basePath = "/admin/workforce_planning", result = result,
				carry = filters.asQueryParameters(), t = t)
	`)
