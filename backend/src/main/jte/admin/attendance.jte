@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.hr.AttendanceDisplay
@import com.workin.backend.platformadmin.hr.AttendanceRecord
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
@param List<AttendanceRecord.EmployeeOption> employeeOptions
@param List<AttendanceRecord.ExceptionTypeOption> exceptionTypes
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
@param Function<String, Boolean> available
!{boolean arabic = "ar".equals(lang);}
!{boolean canWrite = canManage && actionsEnabled;}
<%-- The range, as a map: the pager needs it in every page link AND as hidden
     inputs in the page-size form, and one source keeps those from drifting. --%>
!{java.util.SequencedMap<String, String> carry =
		new java.util.LinkedHashMap<>(filters.asQueryParameters());}
!{carry.put("from", from);}
!{carry.put("to", to);}
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
	available = available,
	pageStyles = java.util.List.of("payroll-pages.css", "hr-pages.css",
			"leave-balance-form.css"),
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
					<div class="filter-field">
						<label class="filter-field__label" for="at_company">${t.apply("company")}</label>
						<input type="number" id="at_company" name="company_id" min="0"
						       placeholder="${t.apply("filter_all")}"
						       value="${filters.companyId() > 0 ? String.valueOf(filters.companyId()) : ""}">
					</div>
				@endif
				<div class="filter-field">
					<label class="filter-field__label" for="at_search">${t.apply("search")}</label>
					<input type="search" id="at_search" name="search" value="${filters.search()}">
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="at_branch">${t.apply("branch")}</label>
					<select id="at_branch" name="filter_branch">
						<option value="0">${t.apply("filter_all")}</option>
						@for(Employee.Option option : branchOptions)
							<option value="${option.id()}"
							        selected="${filters.filterBranch() == option.id()}">${option.name()}</option>
						@endfor
					</select>
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="at_dept">${t.apply("department")}</label>
					<select id="at_dept" name="filter_department">
						<option value="0">${t.apply("filter_all")}</option>
						@for(Employee.Option option : departmentOptions)
							<option value="${option.id()}"
							        selected="${filters.filterDepartment() == option.id()}">${option.name()}</option>
						@endfor
					</select>
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
				<h2 class="data-table-title">${t.apply("att_records")}</h2>
				<div class="data-table-head__actions">
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
						<th class="col-actions">${t.apply("actions")}</th>
					</tr>
				</thead>
				<tbody>
					@for(int index = 0; index < result.data().size(); index++)
						!{AttendanceRecord.Row row = result.data().get(index);}
						<tr>
							<td>${result.from() + index}</td>
							<td class="text-muted">${row.empCode() == null ? "—" : row.empCode()}</td>
							<td class="bold">${row.employeeName()}</td>
							<td>${row.checkIn() == null || row.checkIn().length() < 10
								? "—" : row.checkIn().substring(0, 10)}</td>
							<%-- Whether a punch fell on a Friday is the difference between
							     an absence and a weekend; legacy puts it in its own column
							     rather than leaving the reader to work it out from a date. --%>
							<td class="text-muted">${AttendanceDisplay.dayName(row.checkIn(), arabic)}</td>
							<td>${row.checkIn() == null ? "—" : row.checkIn()}</td>
							<td>${row.checkOut() == null ? "—" : row.checkOut()}</td>
							<td>${row.workedMinutes() == null
								? "—" : (row.workedMinutes() / 60) + ":"
									+ (row.workedMinutes() % 60 < 10 ? "0" : "")
									+ (row.workedMinutes() % 60)}</td>
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
												data-dialog-subject="${row.employeeName()}"
												data-dialog-check_in="${row.checkIn() == null ? "" : row.checkIn().replace(' ', 'T')}"
												data-dialog-check_out="${row.checkOut() == null ? "" : row.checkOut().replace(' ', 'T')}"
												data-dialog-exception_type_id="${row.exceptionTypeId() == null ? "0" : String.valueOf(row.exceptionTypeId())}">${t.apply("edit")}</button>
										<form method="POST" class="row-actions__form" role="none"
										      onsubmit="return confirm('${t.apply("confirm_delete")}')">
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
			@template.admin.pager(basePath = "/admin/attendance", result = result,
					carry = carry, t = t)
		</div>

		<%-- The same range, per employee. Its hours come from raw SQL rather
		     than the engine above, so the two tables can disagree. --%>
		<div class="data-table-card">
			<div class="data-table-head">
				<h2 class="data-table-title">${t.apply("att_aggregate_report")}</h2>
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
							<td class="text-muted">${aggregateResult.from() + index}</td>
							<td class="text-muted">${row.empCode()}</td>
							<td class="bold">${row.employeeName()}</td>
							<td>${row.jobTitleName().isEmpty() ? "—" : row.jobTitleName()}</td>
							<td>${row.daysInPeriod()}</td>
							<td>${row.presentDays()}</td>
							<td>${row.absentDays()}</td>
							<td>${row.exceptionDays()}</td>
							<td>${(row.totalMinutes() / 60) + ":"
								+ (row.totalMinutes() % 60 < 10 ? "0" : "") + (row.totalMinutes() % 60)}</td>
							<td>${(row.paidRestMinutes() / 60) + ":"
								+ (row.paidRestMinutes() % 60 < 10 ? "0" : "") + (row.paidRestMinutes() % 60)}</td>
							<%-- Signed: the column is overtime AND shortfall, and a
							     negative hour count is the half a payroll clerk is
							     looking for. --%>
							<td class="${row.overtimeMinutes() < 0 ? "text-red" : "text-green"}" dir="ltr">${
								(row.overtimeMinutes() < 0 ? "-" : "")
								+ (Math.abs(row.overtimeMinutes()) / 60) + ":"
								+ (Math.abs(row.overtimeMinutes()) % 60 < 10 ? "0" : "")
								+ (Math.abs(row.overtimeMinutes()) % 60)}</td>
						</tr>
					@endfor
					@if(aggregateResult.isEmpty())
						<tr><td colspan="11" class="data-table-empty">${t.apply("no_data")}</td></tr>
					@endif
				</tbody>
			</table>
			</div>
			@template.admin.pager(basePath = "/admin/attendance", result = aggregateResult,
					carry = carry, t = t,
					pageParam = "agg_page")
		@if(canWrite)
			@template.admin.rowDialog(id = "attendance-edit", action = "edit_attendance",
					title = t.apply("edit"), csrfParameterName = csrfParameterName,
					csrfToken = csrfToken, t = t, submitLabel = t.apply("save"),
					fields = @`
						<div class="form-row">
							<label>${t.apply("att_check_in_time")}
								<input type="datetime-local" name="check_in" required
								       data-dialog-field="check_in"></label>
							<label>${t.apply("att_check_out_time")}
								<input type="datetime-local" name="check_out"
								       data-dialog-field="check_out"></label>
						</div>
						<div class="form-row">
							<label>${t.apply("exception")}
								<select name="exception_type_id" data-dialog-field="exception_type_id">
									<option value="0">—</option>
									@for(AttendanceRecord.ExceptionTypeOption option : exceptionTypes)
										<option value="${option.id()}">${option.name()}</option>
									@endfor
								</select></label>
						</div>
					`)

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
					<form method="POST">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="add_attendance">
					<div class="form-row">
						<label for="employee_id">${t.apply("employee_name")}</label>
						<select id="employee_id" name="employee_id" required>
							<option value="0">—</option>
							@for(AttendanceRecord.EmployeeOption option : employeeOptions)
								<option value="${option.id()}">${option.label()}</option>
							@endfor
						</select>
					</div>
					<div class="form-row">
						<label for="check_in">${t.apply("att_check_in_time")}</label>
						<input type="datetime-local" id="check_in" name="check_in" required>
					</div>
					<div class="form-row">
						<label for="check_out">${t.apply("att_check_out_time")}</label>
						<input type="datetime-local" id="check_out" name="check_out">
					</div>
					<div class="form-row">
						<label for="exception_type_id">${t.apply("exception")}</label>
						<select id="exception_type_id" name="exception_type_id">
							<option value="0">—</option>
							@for(AttendanceRecord.ExceptionTypeOption option : exceptionTypes)
								<option value="${option.id()}">${option.name()}</option>
							@endfor
						</select>
					</div>
					<button type="submit" class="btn btn-blue">${t.apply("save")}</button>
				</form>
				</div>
			</div>
		@endif
		</div>
	`)
