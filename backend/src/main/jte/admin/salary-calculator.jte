@import java.util.function.Function
@import com.workin.backend.platformadmin.payroll.EgyptSalaryCalculator
@import com.workin.backend.platformadmin.web.DashboardSession
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param String grossRaw
@param String siRaw
@param String otherRaw
@param EgyptSalaryCalculator.Estimate result = null
@param String currentPage
@param String lang
@param DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
<%-- dashboard/pages/salary_calculator/page.php. Class names and stylesheets
     are the dashboard's own, and the content class is the page's, not the
     shared hr-page: this one is styled by payroll-pages.css plus the
     calculator's own file. --%>
@template.admin.layout(
	title = t.apply("nav_salary_calculator"),
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentAdminPhone = currentAdminPhone,
	currentPage = currentPage,
	lang = lang,
	session = session,
	t = t,
	available = available,
	pageStyles = java.util.List.of("payroll-pages.css", "salary-calculator.css"),
	contentClass = "payroll-page org-page-salary-calculator",
	body = @`
	<div class="salary-calc-notes">
		<h3>${t.apply("salary_notes_title")}</h3>
		<ul>
			<li>${t.apply("salary_note_approx")}</li>
			<li>${t.apply("salary_note_age")}</li>
		</ul>
	</div>

	<div class="salary-calc-grid">
		<div class="salary-calc-card">
			<form method="POST">
				<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
				<div class="salary-calc-field">
					<label>${t.apply("salary_gross_monthly")}</label>
					<input type="text" name="gross" value="${grossRaw}" inputmode="decimal" required>
					<div class="hint">${t.apply("salary_gross_hint")}</div>
				</div>
				<div class="salary-calc-field">
					<label>${t.apply("salary_si_base")}</label>
					<input type="text" name="si_base" value="${siRaw}" inputmode="decimal" placeholder="">
					<div class="hint">${t.apply("salary_si_hint")}</div>
					<div class="hint salary-calc-info-line">
						<span class="salary-calc-info-icon" aria-hidden="true">&#9432;</span>
						${t.apply("salary_allowances_note")}
					</div>
				</div>
				<div class="salary-calc-field">
					<label>${t.apply("salary_other_non_taxable")}</label>
					<input type="text" name="other_non_taxable" value="${otherRaw}" inputmode="decimal">
					<div class="hint">${t.apply("salary_other_non_taxable_hint")}</div>
				</div>
				<div class="salary-calc-actions">
					<button type="submit" class="btn btn-blue btn-calc">&#128202; ${t.apply("salary_calc_btn")}</button>
					<a href="?reset=1" class="btn btn-outline">${t.apply("reset")}</a>
				</div>
			</form>
		</div>

		<div class="salary-calc-card">
			<h3>&#128202; ${t.apply("salary_breakdown")}</h3>
			@if(result != null)
			<div class="salary-net-box">
				<div class="label">${t.apply("salary_net_final")}</div>
				<div class="amount">${result.netMonthlyLabel()} ${t.apply("egp")}</div>
				<div class="sub">${t.apply("salary_monthly")} · ${result.netYearlyLabel()} ${t.apply("egp")} ${t.apply("salary_yearly")}</div>
			</div>
			<%-- The "total deductions" row is a heading with an empty cell beside
			     it -- legacy computes the figure and never prints it. Kept as a
			     divider, as it renders today. --%>
			<ul class="salary-breakdown-list">
				<li><span>${t.apply("salary_gross_monthly")}</span><span class="bold">${result.grossMonthlyLabel()} ${t.apply("egp")}</span></li>
				<li><span>${t.apply("salary_si_base")}</span><span class="bold">${result.socialInsuranceBaseLabel()} ${t.apply("egp")}</span></li>
				<li class="section-title"><span>${t.apply("salary_total_deductions")}</span><span></span></li>
				<li><span>${t.apply("salary_employee_si")}</span><span class="deduct">- ${result.employeeSiLabel()} ${t.apply("egp")}</span></li>
				<li><span>${t.apply("salary_income_tax")}</span><span class="deduct">- ${result.incomeTaxLabel()} ${t.apply("egp")}</span></li>
				<li><span>${t.apply("salary_martyrs")}</span><span class="deduct">- ${result.martyrsFundLabel()} ${t.apply("egp")}</span></li>
				<li><span>${t.apply("salary_employer_si")}</span><span>${result.employerSiLabel()} ${t.apply("egp")}</span></li>
			</ul>
			<p class="hint" style="margin-top:12px;font-size:12px;color:#888780">${t.apply("salary_employer_si_hint")}</p>
			@else
			<div class="salary-breakdown-empty">${t.apply("salary_calc_btn")} — ${t.apply("salary_gross_monthly")}</div>
			@endif
		</div>
	</div>
`)
