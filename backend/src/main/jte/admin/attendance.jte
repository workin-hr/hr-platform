@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.hr.EmployeeDisplay
@import com.workin.backend.platformadmin.hr.AttendanceDisplay
@import com.workin.backend.platformadmin.hr.AttendanceRecord
@import com.workin.backend.platformadmin.hr.EmployeePicker
@import com.workin.backend.platformadmin.hr.LeaveBalance
@import com.workin.backend.platformadmin.hr.Employee
@import com.workin.backend.platformadmin.web.DashboardListFilters
@import com.workin.backend.platformadmin.web.DashboardPage
@import com.workin.backend.platformadmin.web.DashboardSession
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param DashboardPage<AttendanceRecord.Row> result
@param DashboardPage<AttendanceRecord.AggregateRow> aggregateResult
@param DashboardListFilters filters
@param java.util.function.Supplier<java.util.List<com.workin.backend.platformadmin.org.ActiveCompanies.CompanyOption>> companyFilterOptions
@param java.util.function.Supplier<com.workin.backend.platformadmin.web.OrgFilterCascade> orgFilterCascade
@param List<LeaveBalance.EmployeeOption> employeeOptions
@param List<AttendanceRecord.ExceptionTypeOption> exceptionTypes
@param List<AttendanceRecord.ExceptionTypeOption> editExceptionTypes
@param boolean editChoosesType
@param List<Employee.Option> branchOptions
@param List<Employee.Option> departmentOptions
@param String from
@param String to
@param int aggPage
@param boolean showCompanyColumn
@param boolean canManage
@param boolean actionsEnabled
@param String errorKey = null
@param String currentPage
@param String lang
@param DashboardSession session
@param Function<String, String> t
@param String flash = null
@param String flashType = "success"
@param Function<String, Boolean> available
!{boolean arabic = "ar".equals(lang);}
!{boolean canWrite = canManage && actionsEnabled;}
<%-- The range, as a map: the pager needs it in every page link AND as hidden
     inputs in the page-size form, and one source keeps those from drifting.
     query.php's pagerHtml() carries the *other* page number forward by
     replaying the whole current $_GET; this carries the two page numbers
     explicitly instead, so paging one table does not reset the other
     (pager.jte skips a carried entry that names its own pageParam). --%>
!{java.util.SequencedMap<String, String> carry =
		new java.util.LinkedHashMap<>(filters.asQueryParameters());}
!{carry.put("from", from);}
!{carry.put("to", to);}
<%-- filters.page(), not result.page(): pagerHtml() replays the raw $_GET, and
     DashboardPage clamps a page past the last one for display while keeping the
     offset it was asked for. Carrying the clamped value would rewrite a
     bookmarked page=99 into the last page the moment the other pager is used. --%>
!{carry.put("page", String.valueOf(filters.page()));}
!{carry.put("agg_page", String.valueOf(aggPage));}
<%-- dashboard/pages/attendance/page.php.

     Two tables over one range. They do not agree on worked time and are not
     meant to: the detail list runs each punch through the worked-minutes
     engine, the summary sums raw TIMESTAMPDIFF in SQL. See AttendanceStore. --%>
<%-- page.php:134 names three stylesheets, in this order. The port named
     one, so the leave-balance form and the payroll table on this page
     rendered with no rules of their own. --%>
@template.admin.layout(
	title = t.apply("nav_fingerprints"),
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
	contentClass = "payroll-page hr-page",
	pageStyles = java.util.List.of("payroll-pages.css", "hr-pages.css",
			"leave-balance-form.css"),
	pageScripts = java.util.List.of("emp-picker.js", "attendance-form.js"),
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
			      data-selected-branch="${filters.filterBranch()}"
			      data-selected-department="${filters.filterDepartment()}"
			      data-selected-job-title="0">
				@if(!session.isScopedToOneCompany())
					@template.admin.companyFilterField(companyId = filters.companyId(), companies = companyFilterOptions, t = t)
				@endif
				<div class="filter-field">
					<label class="filter-field__label" for="at_branch">${t.apply("branch")}</label>
					<select id="at_branch" name="filter_branch" data-filter-branch>
						<option value="0">${t.apply("filter_all")}</option>
						<%-- org_option_label() (org_helper.php:485-492): "name — company" only
						     when no company is chosen. Department options carry no such suffix;
						     legacy's own toolbar renders the department list plainly. --%>
						@for(Employee.Option option : branchOptions)
							<option value="${option.id()}"
							        selected="${filters.filterBranch() == option.id()}">${option.companyName() == null
									? option.name() : option.name() + " — " + option.companyName()}</option>
						@endfor
					</select>
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="at_dept">${t.apply("department")}</label>
					<select id="at_dept" name="filter_department" data-filter-department>
						<option value="0">${t.apply("filter_all")}</option>
						@for(Employee.Option option : departmentOptions)
							<option value="${option.id()}"
							        selected="${filters.filterDepartment() == option.id()}">${option.name()}</option>
						@endfor
					</select>
				</div>
				<%-- payroll_list_helper.php:75-102: legacy labels this field "employee_name" with
				     att_search_placeholder, and puts it after branch/department, not before. --%>
				<div class="filter-field">
					<label class="filter-field__label" for="at_search">${t.apply("employee_name")}</label>
					<input type="search" id="at_search" name="search" value="${filters.search()}"
					       placeholder="${t.apply("att_search_placeholder")}...">
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="at_from">${t.apply("from_date")}</label>
					<input type="date" id="at_from" name="from" value="${from}">
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="at_to">${t.apply("to_date")}</label>
					<input type="date" id="at_to" name="to" value="${to}">
				</div>
				<button type="submit" class="btn btn-blue">${t.apply("search")}</button>
			</form>
		</div>


		<%-- Punch by punch. The hours column is the engine's answer, and an
		     em dash where it has none, never a zero. --%>
		<div class="data-table-card">
			<div class="data-table-head">
				<%-- page.php:182: the heading reuses the page title's key, with the count. --%>
				<h2 class="data-table-title">${t.apply("nav_fingerprints")} (${result.total()})</h2>
				<div class="data-table-head__actions">
					<%-- D-210: page.php:186 offers this and nothing here posted
					     delete_range. Shown only with a company chosen, as legacy
					     shows it -- without one the range is every company's, and
					     the service refuses it anyway. The confirmation names the
					     range, because "bulk delete" alone does not say how much. --%>
					@if(canWrite && filters.companyId() > 0)
						<form method="POST" class="inline-form"
						      data-confirm="${t.apply("att_range_delete_confirm")}" data-confirm-detail="${from} → ${to}" data-confirm-tone="danger">
							<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
							<input type="hidden" name="action" value="delete_range">
							<input type="hidden" name="company_id" value="${filters.companyId()}">
							<input type="hidden" name="from" value="${from}">
							<input type="hidden" name="to" value="${to}">
							<button type="submit" class="btn btn-red btn-sm">${t.apply("att_delete_range")}</button>
						</form>
					@endif
					@if(canWrite)
						<button type="button" class="btn btn-blue btn-sm"
								onclick="crudOpenAdd('attModal')">+ ${t.apply("add_attendance")}</button>
					@endif
				</div>
			</div>
			<div class="table-wrap">
			<table class="tbl">
				<thead>
					<tr>
						<th>#</th>
						<th>${t.apply("emp_code")}</th>
						<th>${t.apply("employee_name")}</th>
						<th>${t.apply("date")}</th>
						<th>${t.apply("att_day")}</th>
						<th>${t.apply("att_check_in_time")}</th>
						<th>${t.apply("att_check_out_time")}</th>
						<th>${t.apply("hours_worked")}</th>
						<th>${t.apply("exception")}</th>
						@template.admin.actionsHeader(t = t)
					</tr>
				</thead>
				<tbody>
					@for(int index = 0; index < result.data().size(); index++)
						!{AttendanceRecord.Row row = result.data().get(index);}
						<%-- dashboard_employee_display_name(): a blank name reads as an em dash, in
						     the cell and in the row's actions (hr_list_helper.php:69,
						     payroll_list_helper.php:494). --%>
						!{String employeeName = EmployeeDisplay.displayName(row.employeeName(), "—");}
						<tr>
							<td>${result.from() + index}</td>
							<td class="text-muted nowrap"><span data-copy dir="ltr">${row.empCode() == null ? "—" : row.empCode()}</span></td>
							<td class="bold">${employeeName}</td>
							<%-- page.php:202-206. Whether a punch fell on a Friday is the
							     difference between an absence and a weekend; legacy puts it in
							     its own column rather than leaving the reader to work it out. --%>
							<td class="nowrap">${AttendanceDisplay.date(row.checkIn(), arabic)}</td>
							<td>${AttendanceDisplay.dayName(row.checkIn(), arabic)}</td>
							<td dir="ltr">${AttendanceDisplay.time(row.checkIn())}</td>
							@if(row.checkOut() == null || row.checkOut().isEmpty())
								<td dir="ltr"><span class="text-muted">—</span></td>
							@else
								<td dir="ltr">${AttendanceDisplay.time(row.checkOut())}</td>
							@endif
							<td>${AttendanceDisplay.hours(row.workedMinutes())}</td>
							<td>${row.exceptionName() == null ? "—" : row.exceptionName()}</td>
							<td class="col-actions">
								@if(canWrite)
									@template.admin.rowActions(rowId = row.id(), t = t, items = @`
										<%-- D-210: saveEdit existed and nothing posted
										     edit_attendance, so a punch could be deleted but never
										     corrected -- the one record on this surface most likely
										     to be wrong rather than unwanted. The datetime columns
										     are stored "YYYY-MM-DD HH:MM:SS"; datetime-local wants
										     the T. --%>
										<button type="button" role="menuitem" class="row-actions__item"
												data-dialog="attendance-edit"
												data-dialog-id="${row.id()}"
												data-dialog-subject="${employeeName}"
												data-dialog-check_in="${row.checkIn() == null ? "" : row.checkIn().replace(' ', 'T')}"
												data-dialog-check_out="${row.checkOut() == null ? "" : row.checkOut().replace(' ', 'T')}"
												data-dialog-exception_type_id="${row.exceptionTypeId() == null ? "0" : String.valueOf(row.exceptionTypeId())}"
												data-dialog-exception_name="${row.exceptionName() == null ? "—" : row.exceptionName()}">${t.apply("edit")}</button>
										<form method="POST" class="row-actions__form" role="none"
										      data-confirm="${t.apply("confirm_delete")}" data-confirm-tone="danger">
											<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
											<input type="hidden" name="action" value="delete">
											<input type="hidden" name="id" value="${row.id()}">
											<button type="submit" role="menuitem" class="row-actions__item row-actions__item--danger">${t.apply("delete")}</button>
										</form>
									`)
								@else
									<span class="text-muted">—</span>
								@endif
							</td>
						</tr>
					@endfor
					@if(result.isEmpty())
						<tr><td colspan="10" class="data-table-empty">${t.apply("no_data")}</td></tr>
					@endif
				</tbody>
			</table>
			</div>
		</div>
		@template.admin.pager(basePath = "/admin/attendance", result = result,
				carry = carry, t = t)

		<%-- The same range, per employee. Its hours come from raw SQL rather
		     than the engine above, so the two tables can disagree. --%>
		<div class="data-table-card">
			<div class="data-table-head">
				<h2 class="data-table-title">${t.apply("att_aggregate_report")} (${aggregateResult.total()})</h2>
			</div>
			<div class="table-wrap">
			<table class="tbl">
				<thead>
					<tr>
						<th>#</th>
						<th>${t.apply("emp_code")}</th>
						<th>${t.apply("employee_name")}</th>
						<th>${t.apply("job_title")}</th>
						<th>${t.apply("att_days_in_period")}</th>
						<th>${t.apply("att_present_days")}</th>
						<th>${t.apply("att_absent_days")}</th>
						<th>${t.apply("att_exception_days")}</th>
						<th>${t.apply("att_total_work_hours")}</th>
						<th>${t.apply("att_paid_rest_hours")}</th>
						<th>${t.apply("att_overtime_shortage")}</th>
					</tr>
				</thead>
				<tbody>
					@for(int index = 0; index < aggregateResult.data().size(); index++)
						!{AttendanceRecord.AggregateRow row = aggregateResult.data().get(index);}
						<tr>
							<td>${aggregateResult.from() + index}</td>
							<td class="text-muted nowrap">${row.empCode() == null || row.empCode().isEmpty() ? "—" : row.empCode()}</td>
							<%-- page.php:242 prints the name its SQL returns, not through
							     dashboard_employee_display_name(), so a blank one stays blank. --%>
							<td class="bold">${row.employeeName()}</td>
							<td>${row.jobTitleName().isEmpty() ? "—" : row.jobTitleName()}</td>
							<td class="col-center">${row.daysInPeriod()}</td>
							<td class="col-center">${row.presentDays()}</td>
							<td class="col-center">${row.absentDays()}</td>
							<td class="col-center">${row.exceptionDays()}</td>
							<td>${AttendanceDisplay.duration(row.totalMinutes(), t)}</td>
							<td>${AttendanceDisplay.duration(row.paidRestMinutes(), t)}</td>
							<%-- page.php:250. The column is overtime AND shortfall, and the
							     shortfall is the half a payroll clerk is looking for. --%>
							<td class="col-center">
								@if(row.overtimeMinutes() == 0)
									<span class="text-muted">—</span>
								@else
									<span class="att-overtime-badge att-overtime-badge--${row.overtimeMinutes() > 0 ? "plus" : "minus"}">${AttendanceDisplay.overtime(row.overtimeMinutes(), t)}</span>
								@endif
							</td>
						</tr>
					@endfor
					@if(aggregateResult.isEmpty())
						<tr><td colspan="11" class="data-table-empty">${t.apply("no_data")}</td></tr>
					@endif
				</tbody>
			</table>
			</div>
		@if(canWrite)
			@template.admin.rowDialog(id = "attendance-edit", action = "edit_attendance",
					title = t.apply("edit_attendance"), csrfParameterName = csrfParameterName,
					csrfToken = csrfToken, t = t, submitLabel = t.apply("save"),
					fields = @`
						<div class="form-row">
							<label for="att_edit_check_in">${t.apply("check_in")}</label>
							<%-- step="1": a device punch is stored to the second, and the picker
							     keeps the seconds only when the field allows them (D-288). --%>
							<input type="datetime-local" id="att_edit_check_in" name="check_in" required step="1" data-dialog-field="check_in">
						</div>
						<div class="form-row">
							<label for="att_edit_check_out">${t.apply("check_out")}</label>
							<input type="datetime-local" id="att_edit_check_out" name="check_out" step="1" data-dialog-field="check_out">
						</div>
						<div class="form-row">
							@if(editChoosesType)
							<label for="att_edit_exception">${t.apply("exception")} <span class="text-muted">(${t.apply("optional")})</span></label>
							<select name="exception_type_id" id="att_edit_exception" data-dialog-field="exception_type_id">
								<option value="0">—</option>
								<%-- Active types, plus a retired type a listed row carries, marked. row-dialog.js
								     disables a retired option unless the opened row already carries it. --%>
								@for(AttendanceRecord.ExceptionTypeOption option : editExceptionTypes)
									<option value="${option.id()}" data-dialog-current-only="${option.active() ? null : "1"}">${option.name()}@if(!option.active()) — ${t.apply("status_inactive")}@endif</option>
								@endfor
							</select>
							@else
							<%-- No company chosen: every company's rows are listed and a type belongs to one
							     company, so the row keeps its type and changing it needs a company. --%>
							<input type="hidden" name="exception_type_id" data-dialog-field="exception_type_id">
							<p>${t.apply("exception")}: <span data-dialog-field="exception_name"></span></p>
							<small>${t.apply("select_company_first")}</small>
							@endif
						</div>
					`)

			<%-- The page's one copy of the list the add form's employee picker searches. --%>
			<div hidden id="employee-picker-list" data-employees="${EmployeePicker.json(employeeOptions)}"></div>
			<%-- Legacy opens this form in a modal, behind the "+" button above.
			     Rendered inline it sat open over the table on every visit, whether
			     or not anyone was adding anything. Same form, same fields, same
			     POST; .modal-bg is position:fixed, so it lives at the end of the
			     document and out of the reading order. --%>
			<div class="modal-bg" id="attModal">
				<div class="modal" role="dialog" aria-modal="true" aria-labelledby="attModal-title">
					<button type="button" class="modal-close"
							aria-label="${t.apply("cancel")}">&times;</button>
					<h2 id="attModal-title">${t.apply("add_attendance")}</h2>
					<%-- page.php:286-303: novalidate, because attendance-form.js gates the submit
					     itself -- an employee and a check-in -- as legacy's own form does. Legacy
					     marks those two labels " *"; D-259 marks no field on the dashboard that
					     way, so these carry none and the required attribute states it instead. --%>
					<form method="POST" novalidate>
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="add_attendance">
					<div class="form-row">
						<label for="employee_id">${t.apply("employee_name")}</label>
						@template.admin.employeePicker(inputId = "employee_id",
								source = "employee-picker-list", t = t)
					</div>
					<div class="form-row">
						<label for="check_in">${t.apply("check_in")}</label>
						<input type="datetime-local" id="check_in" name="check_in" required>
					</div>
					<div class="form-row">
						<label for="check_out">${t.apply("check_out")}</label>
						<input type="datetime-local" id="check_out" name="check_out">
					</div>
					<div class="form-row">
						<label for="exception_type_id">${t.apply("exception")} <span class="text-muted">(${t.apply("optional")})</span></label>
						<select id="exception_type_id" name="exception_type_id">
							<option value="0">—</option>
							@for(AttendanceRecord.ExceptionTypeOption option : exceptionTypes)
								<option value="${option.id()}">${option.name()}</option>
							@endfor
						</select>
					</div>
					<%-- The save sits in a form-footer, as legacy's does, so it stays put
					     while the fields scroll: .modal is the scroll container. --%>
					<div class="form-footer">
						<button type="submit" class="btn btn-blue" data-att-submit disabled>${t.apply("save")}</button>
					</div>
				</form>
				</div>
			</div>
		@endif
		</div>
		@template.admin.pager(basePath = "/admin/attendance", result = aggregateResult,
				carry = carry, t = t,
				pageParam = "agg_page")
	`)
