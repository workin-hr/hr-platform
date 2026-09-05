@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.hr.Employee
@import com.workin.backend.platformadmin.web.DashboardListFilters
@import com.workin.backend.platformadmin.web.DashboardPage
@import com.workin.backend.platformadmin.web.DashboardSession
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param DashboardPage<Employee> result
@param DashboardListFilters filters
@param List<Employee.Option> branchOptions
@param List<Employee.Option> departmentOptions
@param List<Employee.Option> jobTitleOptions
@param List<Employee.Option> shiftOptions
@param String status
@param long filterBranch
@param long filterDepartment
@param long filterJobTitle
@param String dateFrom
@param String dateTo
@param boolean showCompanyColumn
@param boolean canManage
@param boolean actionsEnabled
@param boolean factorBound
@param Employee editRow = null
@param boolean addOpen = false
@param String errorKey = null
@param String currentPage
@param String lang
@param DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
!{boolean canWrite = canManage && actionsEnabled && factorBound;}
!{int colCount = showCompanyColumn ? 11 : 10;}
<%-- dashboard/pages/employees/page.php. --%>
@template.admin.layout(
	title = t.apply("employees"),
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
						<label class="filter-field__label" for="emp_company">${t.apply("company")}</label>
						<input type="number" id="emp_company" name="company_id" min="0"
						       placeholder="${t.apply("filter_all")}"
						       value="${filters.companyId() > 0 ? String.valueOf(filters.companyId()) : ""}">
					</div>
				@endif
				<div class="filter-field">
					<%-- Six columns: both names, the two concatenated, the code,
					     the id as text and the phone. --%>
					<label class="filter-field__label" for="emp_search">${t.apply("search")}</label>
					<input type="search" id="emp_search" name="search"
					       placeholder="${t.apply("search")}..." value="${filters.search()}">
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="emp_status">${t.apply("status")}</label>
					<select id="emp_status" name="filter">
						<option value="all" selected="${"all".equals(status)}">${t.apply("filter_all")}</option>
						<option value="active" selected="${"active".equals(status)}">${t.apply("status_active")}</option>
						<option value="inactive" selected="${"inactive".equals(status)}">${t.apply("status_inactive")}</option>
					</select>
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="emp_branch_f">${t.apply("branch")}</label>
					<select id="emp_branch_f" name="filter_branch">
						<option value="0">${t.apply("filter_all")}</option>
						@for(Employee.Option option : branchOptions)
							<option value="${option.id()}" selected="${filterBranch == option.id()}">${option.label()}</option>
						@endfor
					</select>
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="emp_dept_f">${t.apply("department")}</label>
					<select id="emp_dept_f" name="filter_department">
						<option value="0">${t.apply("filter_all")}</option>
						@for(Employee.Option option : departmentOptions)
							<option value="${option.id()}" selected="${filterDepartment == option.id()}">${option.label()}</option>
						@endfor
					</select>
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="emp_job_f">${t.apply("job_title")}</label>
					<select id="emp_job_f" name="filter_job_title">
						<option value="0">${t.apply("filter_all")}</option>
						@for(Employee.Option option : jobTitleOptions)
							<option value="${option.id()}" selected="${filterJobTitle == option.id()}">${option.label()}</option>
						@endfor
					</select>
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="emp_from">${t.apply("from_date")}</label>
					<input type="date" id="emp_from" name="date_from" value="${dateFrom}">
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="emp_to">${t.apply("to_date")}</label>
					<input type="date" id="emp_to" name="date_to" value="${dateTo}">
				</div>
				<button type="submit" class="btn btn-blue">${t.apply("search")}</button>
			</form>
		</div>

		@if(addOpen || editRow != null)
			<div class="data-table-card">
				<div class="data-table-head">
					<h2 class="data-table-title">${t.apply(editRow == null ? "add_employee" : "edit")}</h2>
				</div>
				<form method="POST" class="account-form org-form">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="${editRow == null ? "add_employee" : "save_edit"}">
					@if(editRow != null)
						<input type="hidden" name="id" value="${editRow.id()}">
					@else
						<%-- Create path only. An edit takes the company from the
						     row itself: D-176. --%>
						<input type="hidden" name="company_id" value="${filters.companyId()}">
					@endif

					<div class="form-row">
						<label for="first_name">${t.apply("first_name")} *</label>
						<input type="text" id="first_name" name="first_name" required maxlength="255"
						       value="${editRow == null ? "" : editRow.employeeName()}">
					</div>
					<div class="form-row">
						<%-- Not required: legacy checks the first name only. --%>
						<label for="last_name">${t.apply("last_name")}</label>
						<input type="text" id="last_name" name="last_name" maxlength="255">
					</div>
					<div class="form-row">
						<label for="emp_code">${t.apply("emp_code")} *</label>
						<input type="text" id="emp_code" name="employee_code" required maxlength="64"
						       inputmode="numeric" pattern="[0-9]+" autocomplete="off"
						       value="${editRow == null ? "" : editRow.employeeCode()}">
					</div>
					<div class="form-row">
						<label for="branch_id">${t.apply("branch")}</label>
						<select id="branch_id" name="branch_id">
							<option value="0">—</option>
							@for(Employee.Option option : branchOptions)
								<option value="${option.id()}">${option.label()}</option>
							@endfor
						</select>
					</div>
					<div class="form-row">
						<label for="department_id">${t.apply("department")}</label>
						<select id="department_id" name="department_id">
							<option value="0">—</option>
							@for(Employee.Option option : departmentOptions)
								<option value="${option.id()}">${option.label()}</option>
							@endfor
						</select>
					</div>
					<div class="form-row">
						<label for="job_title_id">${t.apply("job_title")}</label>
						<select id="job_title_id" name="job_title_id">
							<option value="0">—</option>
							@for(Employee.Option option : jobTitleOptions)
								<option value="${option.id()}">${option.label()}</option>
							@endfor
						</select>
					</div>
					<div class="form-row">
						<%-- Required on the create path, and the only foreign key
						     legacy insists on there. --%>
						<label for="shift_id">${t.apply("shift")} *</label>
						<select id="shift_id" name="shift_id" required>
							<option value="0">—</option>
							@for(Employee.Option option : shiftOptions)
								<option value="${option.id()}">${option.label()}</option>
							@endfor
						</select>
					</div>
					<div class="form-row">
						<label for="phone">${t.apply("phone")}</label>
						<input type="text" id="phone" name="phone"
						       value="${editRow == null || editRow.phone() == null ? "" : editRow.phone()}">
					</div>
					<div class="form-row">
						<%-- Required only when a phone is given, and checked
						     before the number itself is judged. --%>
						<label for="country_code">${t.apply("country_code")}</label>
						<input type="text" id="country_code" name="country_code"
						       value="${editRow == null || editRow.countryCode() == null ? "" : editRow.countryCode()}">
					</div>
					<div class="form-row">
						<label for="password">${t.apply("password")}</label>
						<input type="password" id="password" name="password" autocomplete="new-password">
					</div>
					<div class="form-row">
						<label for="national_id">${t.apply("national_id")}</label>
						<input type="text" id="national_id" name="national_id" maxlength="64">
					</div>
					<div class="form-row">
						<label for="hire_date">${t.apply("hire_date")}</label>
						<input type="date" id="hire_date" name="hire_date"
						       value="${editRow == null ? "" : editRow.hireDateLabel()}">
					</div>
					<div class="form-row">
						<label for="address">${t.apply("address")}</label>
						<input type="text" id="address" name="address" maxlength="255">
					</div>
					@if(editRow == null)
						<div class="form-row">
							<%-- Create path only: legacy's edit never touches the
							     salary contract. --%>
							<label for="basic_salary">${t.apply("basic_salary")}</label>
							<input type="number" id="basic_salary" name="basic_salary" min="0" step="0.01">
						</div>
					@endif
					<div class="account-form__footer">
						<a href="/admin/employees" class="btn btn-gray">${t.apply("cancel")}</a>
						@if(canWrite)
							<button type="submit" class="btn btn-yellow">${t.apply("save")}</button>
						@endif
					</div>
				</form>
			</div>
		@endif

		<div class="data-table-card">
			<div class="data-table-head">
				<h2 class="data-table-title">${t.apply("employees")} (${result.total()})</h2>
				@if(canManage)
					<a href="/admin/employees?action=add" class="btn btn-blue">+ ${t.apply("add_employee")}</a>
				@endif
			</div>
			<div class="data-table-wrap">
			<table class="data-table">
				<thead>
					<tr>
						<th>#</th>
						@if(showCompanyColumn)<th>${t.apply("company")}</th>@endif
						<th>${t.apply("emp_code")}</th>
						<th>${t.apply("employees")}</th>
						<th>${t.apply("phone")}</th>
						<th>${t.apply("branch")}</th>
						<th>${t.apply("department")}</th>
						<th>${t.apply("job_title")}</th>
						<th>${t.apply("shift")}</th>
						<th>${t.apply("status")}</th>
						<th>${t.apply("actions")}</th>
					</tr>
				</thead>
				<tbody>
					@for(int index = 0; index < result.data().size(); index++)
						!{Employee row = result.data().get(index);}
						<tr>
							<td>${result.from() + index}</td>
							@if(showCompanyColumn)
								<td class="text-muted">${row.companyName() == null ? "—" : row.companyName()}</td>
							@endif
							<td>${row.employeeCode()}</td>
							<td class="bold">${row.employeeName()}</td>
							<td>${row.phoneLabel()}</td>
							<td class="text-muted">${row.branchLabel()}</td>
							<td class="text-muted">${row.departmentLabel()}</td>
							<td class="text-muted">${row.jobTitleLabel()}</td>
							<td class="text-muted">${row.shiftLabel()}</td>
							<td>
								<span class="badge ${row.active() ? "badge-green" : "badge-red"}">
									${t.apply(row.active() ? "status_active" : "status_inactive")}</span>
							</td>
							<td class="col-actions">
								@if(canManage)
									<a href="/admin/employees?action=edit&id=${row.id()}"
									   class="btn btn-outline btn-sm">${t.apply("edit")}</a>
									@if(canWrite)
										<form method="POST" class="inline-form">
											<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
											<input type="hidden" name="action"
											       value="${row.active() ? "deactivate" : "reactivate"}">
											<input type="hidden" name="id" value="${row.id()}">
											<button type="submit" class="btn btn-outline btn-sm">
												${t.apply(row.active() ? "deactivate" : "reactivate")}</button>
										</form>
										<form method="POST" class="inline-form"
										      onsubmit="return confirm('${t.apply("confirm_delete")}')">
											<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
											<input type="hidden" name="action" value="delete">
											<input type="hidden" name="id" value="${row.id()}">
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
						<a href="/admin/employees?page=${page}${filters.asQueryTail()}"
						   class="pager__link${page == result.page() ? " is-current" : ""}">${page}</a>
					@endfor
				</div>
			@endif
		</div>
	`)
