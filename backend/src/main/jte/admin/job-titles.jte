@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.org.ActiveCompanies
@import com.workin.backend.platformadmin.org.JobTitle
@import com.workin.backend.platformadmin.web.DashboardListFilters
@import com.workin.backend.platformadmin.web.DashboardPage
@import com.workin.backend.platformadmin.web.DashboardSession
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param DashboardPage<JobTitle> result
@param DashboardListFilters filters
@param java.util.function.Supplier<java.util.List<com.workin.backend.platformadmin.org.ActiveCompanies.CompanyOption>> companyFilterOptions
@param java.util.function.Supplier<com.workin.backend.platformadmin.web.OrgFilterCascade> orgFilterCascade
@param List<JobTitle.DepartmentOption> departmentOptions
@param List<JobTitle.DepartmentOption> formDepartments = List.of()
@param boolean pickCompany = false
@param List<ActiveCompanies.CompanyOption> companyOptions = List.of()
@param String departmentsByCompany = "{}"
@param boolean showCompanyColumn
@param boolean canManage
@param boolean actionsEnabled
@param JobTitle editRow = null
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
!{int colCount = showCompanyColumn ? 9 : 8;}
<%-- dashboard/pages/job_titles/page.php. --%>
@template.admin.layout(
	title = t.apply("job_titles"),
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
	pageScripts = java.util.List.of("job-title-form.js"),
	body = @`
		@if(errorKey != null)
			<div class="flash flash-error">${t.apply(errorKey)}</div>
		@endif
		@if(canManage && !actionsEnabled)
			<div class="flash flash-warning">${t.apply("admin_actions_disabled")}</div>
		@endif

		<div class="toolbar page-toolbar page-toolbar-filters">
			<%-- org_filter_cascade_form_attrs() (org_helper.php:450-466), for org-filter-cascade.js. --%>
			!{var cascade = orgFilterCascade.get();}
			<form method="GET" class="toolbar-form toolbar-form--labeled"
			      data-org-filters="${cascade.attached() ? "1" : null}"
			      data-branches-by-company="${cascade.branchesByCompany()}"
			      data-departments-by-company="${cascade.departmentsByCompany()}"
			      data-departments-by-branch="${cascade.departmentsByBranch()}"
			      data-job-titles-by-dept="${cascade.jobTitlesByDepartment()}"
			      data-filter-all="${t.apply("filter_all")}"
			      data-select-company-msg="${t.apply("filter_all_companies")}"
			      data-pick-branch-first-msg="${t.apply("pick_branch")}"
			      data-pick-dept-first-msg="${t.apply("pick_department")}"
			      data-selected-branch="0"
			      data-selected-department="${filters.filterDepartment()}"
			      data-selected-job-title="0">
				@if(!session.isScopedToOneCompany())
					@template.admin.companyFilterField(companyId = filters.companyId(), companies = companyFilterOptions, t = t)
				@endif
				<div class="filter-field">
					<label class="filter-field__label" for="jt_search">${t.apply("job_title")}</label>
					<input type="search" id="jt_search" name="search"
					       placeholder="${t.apply("search")}..." value="${filters.search()}">
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="jt_dept_f">${t.apply("department")}</label>
					<select name="filter_department" id="jt_dept_f" data-filter-department>
						<option value="0">${t.apply("filter_all")}</option>
						@for(JobTitle.DepartmentOption option : departmentOptions)
							<option value="${option.id()}" selected="${filters.filterDepartment() == option.id()}">
								${option.companyName() == null ? option.name()
									: option.name() + " — " + option.companyName()}</option>
						@endfor
					</select>
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="jt_status">${t.apply("status")}</label>
					<select id="jt_status" name="filter">
						<option value="all" selected="${"all".equals(filters.status())}">${t.apply("filter_all")}</option>
						<option value="active" selected="${"active".equals(filters.status())}">${t.apply("status_active")}</option>
						<option value="inactive" selected="${"inactive".equals(filters.status())}">${t.apply("status_inactive")}</option>
					</select>
				</div>
				<button type="submit" class="btn btn-blue">${t.apply("search")}</button>
			</form>
		</div>

		@if(addOpen || editRow != null)
			<%-- page.php:167/187 renders this over modal-bg open, in
			     modal modal--org-form -- a form panel org-form.css styles.
			     The port had it as a data-table-card in the page flow, which
			     is a table card, and wrapped the form in account-form
			     org-form, which no stylesheet defines here or in legacy. --%>
			<div class="modal-bg open" id="jobTitleModal">
				<div class="modal modal--org-form">
					<h2>${editRow == null ? t.apply("add_job_title")
							: t.apply("edit") + ": " + editRow.name()}</h2>
					<form method="POST">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="${editRow == null ? "add" : "save_edit"}">
					@if(editRow != null)
						<input type="hidden" name="id" value="${editRow.id()}">
					@endif
					@if(editRow != null)
						<input type="hidden" name="company_id" value="${editRow.companyId()}">
					@endif

					<%-- The rows are a grid in legacy (_job_title_form.php), with the wide ones
					     marked "org-form-span-2". Without the wrapper every field is a
					     full-width band. The data-* attributes are what job-title-form.js
					     reads; unlike legacy there is no data-fixed-company, so an edit or a
					     filtered add keeps the list rendered here instead of a re-render from
					     active departments only, which would drop a retired one the row has. --%>
					<div class="org-form-grid"
					     data-org-jt-form
					     data-departments="${departmentsByCompany}"
					     data-selected-department=""
					     data-select-company-msg="${t.apply("select_company_first_department")}">
					@if(editRow == null)
						@template.admin.companyField(session = session, companyId = filters.companyId(),
								companies = companyOptions, inputId = "jt_add_company", cascade = "jt", t = t)
					@endif
					<div class="form-row org-form-span-2">
						<label for="name">${t.apply("job_title")}</label>
						<input type="text" id="name" name="name" required maxlength="255"
						       value="${editRow == null ? "" : editRow.name()}">
					</div>
					<div class="form-row org-form-span-2">
						<%-- Optional: a job title can exist with no department, and 0
						     is how "none" is posted. --%>
						<label for="department_id">${t.apply("department")}</label>
						<select id="department_id" name="department_id" data-jt-department
						        disabled="${pickCompany}">
							@if(pickCompany)
								<option value="0">${t.apply("select_company_first_department")}</option>
							@else
							<option value="0">—</option>
							@endif
							@for(JobTitle.DepartmentOption option : formDepartments)
								<option value="${option.id()}"
								        selected="${editRow != null && editRow.departmentId() != null
									        && editRow.departmentId() == option.id()}">
									${option.companyName() == null ? option.name()
										: option.name() + " — " + option.companyName()}</option>
							@endfor
						</select>
					</div>
					<div class="form-row">
						<%-- Required and strictly positive: the only numeric rule in
						     the four org pages. --%>
						<label for="work_hours">${t.apply("work_hours")}</label>
						<input type="number" id="work_hours" name="work_hours" required
						       min="0.5" step="0.5"
						       value="${editRow == null || editRow.workHours() == null ? ""
							       : editRow.workHours().stripTrailingZeros().toPlainString()}">
					</div>
					@if(editRow != null)
						<div class="form-row org-form-span-2">
							<label for="is_active">${t.apply("status")}</label>
							<select id="is_active" name="is_active">
								<option value="1" selected="${editRow.active()}">${t.apply("status_active")}</option>
								<option value="0" selected="${!editRow.active()}">${t.apply("status_inactive")}</option>
							</select>
						</div>
					@endif
										</div>

					<div class="form-footer">
						<a href="/admin/job_titles" class="btn btn-gray">${t.apply("cancel")}</a>
						@if(canWrite)
							<button type="submit" class="btn btn-blue">${t.apply("save")}</button>
						@endif
					</div>
					</form>
				</div>
			</div>
		@endif

		<div class="data-table-card">
			<div class="data-table-head">
				<h2 class="data-table-title">${t.apply("job_titles")} (${result.total()})</h2>
				@if(canManage)
					<a href="/admin/job_titles?action=add" class="btn btn-blue">+ ${t.apply("add_job_title")}</a>
				@endif
			</div>
			<div class="table-wrap">
			<table class="tbl">
				<thead>
					<tr>
						<th>#</th>
						@if(showCompanyColumn)<th>${t.apply("company")}</th>@endif
						<th>${t.apply("job_title")}</th>
						<th>${t.apply("department")}</th>
						<th>${t.apply("work_hours")}</th>
						<th>${t.apply("employees")}</th>
						<th>${t.apply("status")}</th>
						<th>${t.apply("reg_date")}</th>
						@template.admin.actionsHeader(t = t)
					</tr>
				</thead>
				<tbody>
					@for(int index = 0; index < result.data().size(); index++)
						!{JobTitle row = result.data().get(index);}
						<tr>
							<td>${result.from() + index}</td>
							@if(showCompanyColumn)
								<td class="text-muted">${row.companyName() == null ? "—" : row.companyName()}</td>
							@endif
							<td class="bold">${row.name()}</td>
							<td>${row.departmentDisplay()}</td>
							<td><span class="badge badge-gray">${row.workHoursDisplay()}</span></td>
							<td><span class="badge badge-blue">${row.employeeCount()}</span></td>
							<td>
								@template.admin.statusBadge(status = row.active() ? "active" : "suspended", t = t)
							</td>
							<td class="nowrap">${row.createdDate()}</td>
							<td class="col-actions">
								@if(canManage)
									@template.admin.rowActions(rowId = row.id(), t = t, items = @`
										<a href="/admin/job_titles?action=edit&id=${row.id()}"
										   role="menuitem" class="row-actions__item">${t.apply("edit")}</a>
										@if(canWrite)
											<form method="POST" class="row-actions__form" role="none"
											      data-confirm="${t.apply("confirm_delete")}" data-confirm-tone="danger">
												<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
												<input type="hidden" name="action" value="delete">
												<input type="hidden" name="id" value="${row.id()}">
												<input type="hidden" name="company_id" value="${row.companyId()}">
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
						<tr><td colspan="${colCount}" class="data-table-empty">${t.apply("no_data")}</td></tr>
					@endif
				</tbody>
			</table>
			</div>
		</div>
		@template.admin.pager(basePath = "/admin/job_titles", result = result,
				carry = filters.asQueryParameters(), t = t)
	`)
