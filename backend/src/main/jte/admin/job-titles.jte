@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.org.JobTitle
@import com.workin.backend.platformadmin.web.DashboardListFilters
@import com.workin.backend.platformadmin.web.DashboardPage
@import com.workin.backend.platformadmin.web.DashboardSession
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param DashboardPage<JobTitle> result
@param DashboardListFilters filters
@param List<JobTitle.DepartmentOption> departmentOptions
@param boolean showCompanyColumn
@param boolean canManage
@param boolean actionsEnabled
@param boolean factorBound
@param JobTitle editRow = null
@param boolean addOpen = false
@param String errorKey = null
@param String currentPage
@param String lang
@param DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
!{boolean canWrite = canManage && actionsEnabled && factorBound;}
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
	available = available,
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
				@if(!session.isScopedToOneCompany())
					<div class="filter-field">
						<label class="filter-field__label" for="jt_company">${t.apply("company")}</label>
						<input type="number" id="jt_company" name="company_id" min="0"
						       placeholder="${t.apply("filter_all")}"
						       value="${filters.companyId() > 0 ? String.valueOf(filters.companyId()) : ""}">
					</div>
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
									: option.companyName() + " — " + option.name()}</option>
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
			<div class="data-table-card">
				<div class="data-table-head">
					<h2 class="data-table-title">${t.apply(editRow == null ? "add_job_title" : "edit")}</h2>
				</div>
				<form method="POST" class="account-form org-form">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="${editRow == null ? "add" : "save_edit"}">
					@if(editRow != null)
						<input type="hidden" name="id" value="${editRow.id()}">
					@endif
					<input type="hidden" name="company_id"
					       value="${editRow != null ? editRow.companyId() : filters.companyId()}">

					<div class="form-row">
						<label for="name">${t.apply("job_title")}</label>
						<input type="text" id="name" name="name" required maxlength="255"
						       value="${editRow == null ? "" : editRow.name()}">
					</div>
					<div class="form-row">
						<%-- Optional: a job title can exist with no department, and 0
						     is how "none" is posted. --%>
						<label for="department_id">${t.apply("department")}</label>
						<select id="department_id" name="department_id">
							<option value="0">—</option>
							@for(JobTitle.DepartmentOption option : departmentOptions)
								<option value="${option.id()}"
								        selected="${editRow != null && editRow.departmentId() != null
									        && editRow.departmentId() == option.id()}">
									${option.companyName() == null ? option.name()
										: option.companyName() + " — " + option.name()}</option>
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
						<div class="form-row">
							<label for="is_active">${t.apply("status")}</label>
							<select id="is_active" name="is_active">
								<option value="1" selected="${editRow.active()}">${t.apply("status_active")}</option>
								<option value="0" selected="${!editRow.active()}">${t.apply("status_inactive")}</option>
							</select>
						</div>
					@endif
					<div class="account-form__footer">
						<a href="/admin/job_titles" class="btn btn-gray">${t.apply("cancel")}</a>
						@if(canWrite)
							<button type="submit" class="btn btn-yellow">${t.apply("save")}</button>
						@endif
					</div>
				</form>
			</div>
		@endif

		<div class="data-table-card">
			<div class="data-table-head">
				<h2 class="data-table-title">${t.apply("job_titles")} (${result.total()})</h2>
				@if(canManage)
					<a href="/admin/job_titles?action=add" class="btn btn-blue">+ ${t.apply("add_job_title")}</a>
				@endif
			</div>
			<div class="data-table-wrap">
			<table class="data-table">
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
						<th>${t.apply("actions")}</th>
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
							<td class="text-muted">${row.departmentDisplay()}</td>
							<td><span class="badge badge-gray">${row.workHoursDisplay()}</span></td>
							<td><span class="badge badge-blue">${row.employeeCount()}</span></td>
							<td>
								<span class="badge ${row.active() ? "badge-green" : "badge-red"}">
									${t.apply(row.active() ? "status_active" : "status_suspended")}</span>
							</td>
							<td>${row.createdDate()}</td>
							<td class="col-actions">
								@if(canManage)
									<a href="/admin/job_titles?action=edit&id=${row.id()}"
									   class="btn btn-outline btn-sm">${t.apply("edit")}</a>
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
						<a href="/admin/job_titles?page=${page}${filters.asQueryTail()}"
						   class="pager__link${page == result.page() ? " is-current" : ""}">${page}</a>
					@endfor
				</div>
			@endif
		</div>
	`)
