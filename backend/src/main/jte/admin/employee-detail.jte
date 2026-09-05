@import java.util.function.Function
@import com.workin.backend.platformadmin.hr.EmployeeDetail
@import com.workin.backend.platformadmin.web.DashboardSession
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param EmployeeDetail detail
@param boolean canManage
@param boolean factorBound
@param String currentPage
@param String lang
@param DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
<%-- dashboard/pages/employees/detail.php, reached in legacy as
     employee_detail.php through a rewrite. Read-only. --%>
@template.admin.layout(
	title = t.apply("employee"),
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentAdminPhone = currentAdminPhone,
	currentPage = currentPage,
	lang = lang,
	session = session,
	t = t,
	available = available,
	contentClass = "",
	body = @`
		<div class="toolbar page-toolbar">
			<a href="/admin/employees" class="btn btn-outline btn-sm">← ${t.apply("back")}</a>
			<form method="GET" class="toolbar-form">
				<input type="hidden" name="id" value="${detail.employee().id()}">
				<select name="month">
					@for(int m = 1; m <= 12; m++)
						<option value="${m}" selected="${m == detail.month()}">${m}</option>
					@endfor
				</select>
				<select name="year">
					@for(int y = 2023; y <= 2026; y++)
						<option value="${y}" selected="${y == detail.year()}">${y}</option>
					@endfor
				</select>
				<button type="submit" class="btn btn-blue btn-sm">${t.apply("filter")}</button>
			</form>
		</div>

		<div class="data-table-card">
			<div class="data-table-head">
				<h2 class="data-table-title">${detail.employee().employeeName()}
					<span class="badge ${detail.employee().active() ? "badge-green" : "badge-red"}">
						${t.apply(detail.employee().active() ? "status_active" : "status_inactive")}</span>
				</h2>
			</div>
			<div class="detail-grid">
				<span>${t.apply("emp_code")}: <strong>${detail.employee().employeeCode()}</strong></span>
				<span>${t.apply("company_phone")}: <strong>${detail.employee().phoneLabel()}</strong></span>
				<span>${t.apply("company")}: <strong>${detail.employee().companyName()}</strong></span>
				<%-- Legacy shows "all branches" for an employee with none, not a
				     dash: the blank means unrestricted, not unknown. --%>
				<span>${t.apply("branch")}: <strong>${detail.employee().branchName() == null
					|| detail.employee().branchName().isEmpty()
					? t.apply("employee_all_branches") : detail.employee().branchName()}</strong></span>
				<span>${t.apply("department")}: <strong>${detail.employee().departmentLabel()}</strong></span>
				<span>${t.apply("hire_date")}: <strong>${detail.employee().hireDateLabel()}</strong></span>
			</div>
		</div>

		<div class="stats-grid">
			<div class="stat-card green"><div class="stat-num">${detail.daysPresent()}</div>
				<div class="stat-label">${t.apply("days_present")}</div></div>
			<div class="stat-card blue"><div class="stat-num">${detail.hoursWorked().toPlainString()}</div>
				<div class="stat-label">${t.apply("hours_worked")}</div></div>
			<div class="stat-card yellow"><div class="stat-num">${detail.remainingLeaveLabel()}</div>
				<div class="stat-label">${t.apply("remaining_days")}</div></div>
			<div class="stat-card green"><div class="stat-num">${detail.netSalaryLabel()}</div>
				<div class="stat-label">${t.apply("net_salary")}</div></div>
			<%-- Labelled basic_salary, showing the contract total. Legacy's
			     mislabel, reproduced. --%>
			<div class="stat-card blue"><div class="stat-num">${detail.contractTotalLabel()}</div>
				<div class="stat-label">${t.apply("basic_salary")}</div></div>
			<div class="stat-card red"><div class="stat-num">${detail.penaltyCount()}</div>
				<div class="stat-label">${t.apply("penalties")}</div></div>
		</div>

		<div class="data-table-card">
			<div class="data-table-head">
				<h2 class="data-table-title">${t.apply("attendance")} — ${detail.month()}/${detail.year()}</h2>
			</div>
			<div class="data-table-wrap">
			<table class="data-table">
				<thead><tr><th>${t.apply("date")}</th><th>${t.apply("check_in")}</th>
					<th>${t.apply("check_out")}</th><th>${t.apply("method")}</th>
					<th>${t.apply("hours_worked")}</th></tr></thead>
				<tbody>
					@for(EmployeeDetail.AttendanceDay day : detail.attendance())
						<tr>
							<td>${day.day()}</td>
							<td>${day.checkIn()}</td>
							<td>${day.checkOutLabel()}</td>
							<td>${day.method()}</td>
							<td>${day.hours() == null ? t.apply("still_working") : day.hours().toPlainString()}</td>
						</tr>
					@endfor
					@if(detail.attendance().isEmpty())
						<tr><td colspan="5" class="data-table-empty">${t.apply("no_data")}</td></tr>
					@endif
				</tbody>
			</table>
			</div>
		</div>

		<div class="data-table-card">
			<div class="data-table-head"><h2 class="data-table-title">${t.apply("requests")}</h2></div>
			<div class="data-table-wrap">
			<table class="data-table">
				<thead><tr><th>${t.apply("request_type")}</th><th>${t.apply("from_date")}</th>
					<th>${t.apply("to_date")}</th><th>${t.apply("status")}</th></tr></thead>
				<tbody>
					@for(EmployeeDetail.Request row : detail.requests())
						<tr><td>${row.typeName()}</td><td>${row.fromDate()}</td>
							<td>${row.toDate()}</td><td>${row.status()}</td></tr>
					@endfor
					@if(detail.requests().isEmpty())
						<tr><td colspan="4" class="data-table-empty">${t.apply("no_data")}</td></tr>
					@endif
				</tbody>
			</table>
			</div>
		</div>

		<div class="data-table-card">
			<div class="data-table-head"><h2 class="data-table-title">${t.apply("penalties")}</h2></div>
			<div class="data-table-wrap">
			<table class="data-table">
				<thead><tr><th>${t.apply("penalty_date")}</th><th>${t.apply("penalty_type")}</th>
					<th>${t.apply("penalty_days")}</th><th>${t.apply("applied_payroll")}</th></tr></thead>
				<tbody>
					@for(EmployeeDetail.Penalty row : detail.penalties())
						<tr><td>${row.penaltyDate()}</td><td>${row.penaltyType()}</td>
							<td>${row.penaltyDays() == null ? "—" : row.penaltyDays().toPlainString()}</td>
							<td>${t.apply(row.appliedToPayroll() ? "status_active" : "status_inactive")}</td></tr>
					@endfor
					@if(detail.penalties().isEmpty())
						<tr><td colspan="4" class="data-table-empty">${t.apply("no_data")}</td></tr>
					@endif
				</tbody>
			</table>
			</div>
		</div>

		<div class="data-table-card">
			<div class="data-table-head"><h2 class="data-table-title">${t.apply("advances")}</h2></div>
			<div class="data-table-wrap">
			<table class="data-table">
				<thead><tr><th>${t.apply("advance_amount")}</th><th>${t.apply("remaining")}</th>
					<th>${t.apply("status")}</th></tr></thead>
				<tbody>
					@for(EmployeeDetail.Advance row : detail.advances())
						<tr><td>${row.amount() == null ? "—" : row.amount().toPlainString()}</td>
							<td>${row.remaining() == null ? "—" : row.remaining().toPlainString()}</td>
							<td>${row.status()}</td></tr>
					@endfor
					@if(detail.advances().isEmpty())
						<tr><td colspan="3" class="data-table-empty">${t.apply("no_data")}</td></tr>
					@endif
				</tbody>
			</table>
			</div>
		</div>

		@if(detail.payslip() != null)
			<div class="data-table-card">
				<div class="data-table-head">
					<h2 class="data-table-title">${t.apply("payroll")} —
						${detail.payslip().month()}/${detail.payslip().year()}</h2>
				</div>
				<div class="detail-grid">
					<span>${t.apply("basic_salary")}: <strong>${detail.payslip().basicSalary().toPlainString()}</strong></span>
					<span>${t.apply("allowances")}: <strong>${detail.payslip().allowances().toPlainString()}</strong></span>
					<span>${t.apply("overtime_pay")}: <strong>${detail.payslip().overtimePay().toPlainString()}</strong></span>
					<span>${t.apply("penalties")}: <strong>${detail.payslip().penaltiesTotal().toPlainString()}</strong></span>
					<span>${t.apply("advance_deduction")}: <strong>${detail.payslip().advanceDeduction().toPlainString()}</strong></span>
					<span>${t.apply("net_salary")}: <strong>${detail.payslip().netSalary().toPlainString()}</strong></span>
				</div>
			</div>
		@endif

		@if(!detail.documents().isEmpty())
			<div class="data-table-card">
				<div class="data-table-head"><h2 class="data-table-title">${t.apply("commercial_reg")}</h2></div>
				<div class="data-table-wrap">
				<table class="data-table">
					<tbody>
						@for(EmployeeDetail.Document row : detail.documents())
							<tr><td>${row.docType()}</td><td>${row.uploadedAt()}</td></tr>
						@endfor
					</tbody>
				</table>
				</div>
			</div>
		@endif
	`)
