@import java.util.function.Function
@import com.workin.backend.platformadmin.hr.EmployeeDetail
@import com.workin.backend.platformadmin.hr.EmployeeDisplay
@import com.workin.backend.platformadmin.hr.PayrollDisplay
@import com.workin.backend.platformadmin.web.DashboardSession
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param EmployeeDetail detail
@param boolean canManage
@param String currentPage
@param String lang
@param DashboardSession session
@param Function<String, String> t
@param String flash = null
@param String flashType = "success"
@param Function<String, Boolean> available
<%-- dashboard/pages/employees/detail.php, reached in legacy as
     employee_detail.php through a rewrite. Read-only. --%>
<%-- detail.php is served under pages/employees, whose page.php:234 names
     these four -- what a browser loads on legacy's own detail view. --%>
@template.admin.layout(
	title = t.apply("employee") + " — " + detail.employee().employeeName(),
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
	pageStyles = java.util.List.of("hr-pages.css", "company-add.css",
			"org-form.css", "employee-form.css"),
	contentClass = "",
	body = @`
		<%-- detail.php writes its layout in style attributes. The emp-detail-* classes in
		     admin-extra.css are those attributes, value for value. --%>
		<div class="emp-detail-bar">
			<a href="/admin/employees" class="btn btn-outline btn-sm">← ${t.apply("back")}</a>
			<form method="GET">
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

		<div class="emp-detail-card">
			<%-- Legacy falls back to ui-avatars.com when the photo fails to load. The fallback is
			     drawn locally instead, as the lists draw theirs (img-fallback.js). --%>
			@if(detail.employee().photoUrl() == null || detail.employee().photoUrl().isEmpty())
				<div class="emp-detail-avatar">${EmployeeDisplay.initials(detail.employee().employeeName())}</div>
			@else
				<img src="${detail.employee().photoUrl()}" alt="" class="emp-detail-photo"
				     data-fallback-initials="${EmployeeDisplay.initials(detail.employee().employeeName())}"
				     data-fallback-class="emp-detail-avatar">
			@endif
			<div>
				<div class="emp-detail-name">${detail.employee().employeeName()}
					@template.admin.statusBadge(status = detail.employee().active() ? "1" : "0", t = t)</div>
				<div class="emp-detail-fields">
					<span>&#9632; ${t.apply("emp_code")}: <strong>${detail.employee().employeeCode()}</strong></span>
					<span>&#9632; ${t.apply("company_phone")}: <strong>${detail.employee().phone() == null
						? "" : detail.employee().phone()}</strong></span>
					<span>&#9632; ${t.apply("company")}: <strong>${detail.employee().companyName()}</strong></span>
					<%-- Legacy shows "all branches" for an employee with none, not a
					     dash: the blank means unrestricted, not unknown. --%>
					<span>&#9632; ${t.apply("branch")}: <strong>${detail.employee().branchName() == null
						|| detail.employee().branchName().isEmpty()
						? t.apply("employee_all_branches") : detail.employee().branchName()}</strong></span>
					<span>&#9632; ${t.apply("department")}: <strong>${detail.employee().departmentName() == null
						? "—" : detail.employee().departmentName()}</strong></span>
					<span>&#9632; ${t.apply("hire_date")}: <strong>${detail.employee().hireDate() == null
						? "—" : detail.employee().hireDate()}</strong></span>
				</div>
			</div>
		</div>

		<div class="stats-grid emp-detail-stats">
			<div class="stat-card green"><div class="stat-num">${detail.daysPresent()}</div>
				<div class="stat-label">${t.apply("days_present")}</div></div>
			<div class="stat-card blue"><div class="stat-num">${detail.hoursWorkedLabel()}</div>
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

		<div class="emp-detail-tables">
			<div>
				<div class="section-head"><h2>${t.apply("attendance")} — ${detail.month()}/${detail.year()}</h2></div>
				<div class="table-wrap"><table class="tbl">
					<thead><tr><th>${t.apply("date")}</th><th>${t.apply("check_in")}</th>
						<th>${t.apply("check_out")}</th><th>${t.apply("hours_worked")}</th>
						<th>${t.apply("method")}</th></tr></thead>
					<tbody>
						@for(EmployeeDetail.AttendanceDay day : detail.attendance())
							<tr>
								<td>${day.day()}</td>
								<td>${day.checkInTime()}</td>
								<td>@if(day.open())<span class="badge badge-yellow">${t.apply("still_working")}</span>@else${day.checkOutTime()}@endif</td>
								<td>${day.hoursLabel()}</td>
								<td>${day.method()}</td>
							</tr>
						@endfor
						@if(detail.attendance().isEmpty())
							<tr><td colspan="5" class="emp-detail-empty emp-detail-empty--tall">${t.apply("no_data")}</td></tr>
						@endif
					</tbody>
				</table></div>
			</div>
			<div>
				<div class="section-head"><h2>${t.apply("requests")}</h2></div>
				<div class="table-wrap"><table class="tbl">
					<thead><tr><th>${t.apply("request_type")}</th><th>${t.apply("from_date")}</th>
						<th>${t.apply("to_date")}</th><th>${t.apply("status")}</th></tr></thead>
					<tbody>
						@for(EmployeeDetail.Request row : detail.requests())
							<tr><td>${row.typeName()}</td><td>${row.fromDate()}</td><td>${row.toDate()}</td>
								<td>@template.admin.statusBadge(status = row.status(), t = t)</td></tr>
						@endfor
						@if(detail.requests().isEmpty())
							<tr><td colspan="4" class="emp-detail-empty">${t.apply("no_data")}</td></tr>
						@endif
					</tbody>
				</table></div>
			</div>
			<div>
				<div class="section-head"><h2>${t.apply("penalties")}</h2></div>
				<div class="table-wrap"><table class="tbl">
					<thead><tr><th>${t.apply("penalty_type")}</th><th>${t.apply("penalty_days")}</th>
						<th>${t.apply("penalty_date")}</th><th>${t.apply("applied_payroll")}</th></tr></thead>
					<tbody>
						@for(EmployeeDetail.Penalty row : detail.penalties())
							<tr><td>${row.penaltyType()}</td>
								<td class="text-red">${row.penaltyDays() == null ? "" : row.penaltyDays().toPlainString()}</td>
								<td>${row.penaltyDate()}</td>
								<td>@template.admin.statusBadge(status = row.appliedToPayroll() ? "1" : "0", t = t)</td></tr>
						@endfor
						@if(detail.penalties().isEmpty())
							<tr><td colspan="4" class="emp-detail-empty">${t.apply("no_data")}</td></tr>
						@endif
					</tbody>
				</table></div>
			</div>
			<div>
				<div class="section-head"><h2>${t.apply("advances")}</h2></div>
				<div class="table-wrap"><table class="tbl">
					<thead><tr><th>${t.apply("advance_amount")}</th><th>${t.apply("remaining")}</th>
						<th>${t.apply("status")}</th></tr></thead>
					<tbody>
						@for(EmployeeDetail.Advance row : detail.advances())
							<tr><td>${row.amountDisplay()}</td>
								<td class="${row.stillOwed() ? "text-red" : "text-green"}">${row.remainingDisplay()}</td>
								<td>@template.admin.statusBadge(status = row.status(), t = t)</td></tr>
						@endfor
						@if(detail.advances().isEmpty())
							<tr><td colspan="3" class="emp-detail-empty">${t.apply("no_data")}</td></tr>
						@endif
					</tbody>
				</table></div>
			</div>
		</div>

		@if(detail.payslip() != null)
			<div class="emp-detail-section">
				<div class="section-head"><h2>${t.apply("payroll")} — ${detail.payslip().month()}/${detail.payslip().year()}</h2></div>
				<div class="emp-detail-payslip">
					<div class="emp-detail-figures">
						<div><div class="text-muted">${t.apply("basic_salary")}</div><div class="bold">${PayrollDisplay.money(detail.payslip().basicSalary())}</div></div>
						<div><div class="text-muted">${t.apply("allowances")}</div><div class="bold">${PayrollDisplay.money(detail.payslip().allowances())}</div></div>
						<div><div class="text-muted">${t.apply("overtime_pay")}</div><div class="bold text-green">${PayrollDisplay.money(detail.payslip().overtimePay())}</div></div>
						<div><div class="text-muted">${t.apply("penalties")}</div><div class="bold text-red">${PayrollDisplay.money(detail.payslip().penaltiesTotal())}</div></div>
						<%-- Labelled medical insurance and showing advances_deduction, as legacy's
						     line does (detail.php:124). --%>
						<div><div class="text-muted">${t.apply("medical_insurance_deduction")}</div><div class="bold text-red">${PayrollDisplay.money(detail.payslip().advancesDeduction())}</div></div>
						<div><div class="text-muted">${t.apply("advance_deduction")}</div><div class="bold text-red">${PayrollDisplay.money(detail.payslip().advanceDeduction())}</div></div>
						<%-- The currency is legacy's literal, in both languages. --%>
						<div class="emp-detail-net"><div class="text-muted">${t.apply("net_salary")}</div><div class="emp-detail-net-value">${PayrollDisplay.money(detail.payslip().netSalary())} ج.م</div></div>
					</div>
				</div>
			</div>
		@endif

		@if(!detail.documents().isEmpty())
			<div class="emp-detail-section">
				<div class="section-head"><h2>${t.apply("commercial_reg")}</h2></div>
				<div class="emp-detail-docs">
					@for(EmployeeDetail.Document doc : detail.documents())
						@if(doc.href() == null)
							<span class="emp-detail-doc">${doc.docType()}</span>
						@else
							<a href="${doc.href()}" target="_blank" rel="noopener" class="emp-detail-doc">${doc.docType()}</a>
						@endif
					@endfor
				</div>
			</div>
		@endif
	`)
