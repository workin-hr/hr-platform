@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.hr.Employee
@import com.workin.backend.platformadmin.hr.EmployeeDisplay
@import com.workin.backend.platformadmin.org.ActiveCompanies
@import com.workin.backend.platformadmin.web.DashboardListFilters
@import com.workin.backend.platformadmin.web.DashboardPage
@import com.workin.backend.platformadmin.web.DashboardSession
@import com.workin.backend.platformadmin.web.PhoneCountryChoices
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param DashboardPage<Employee> result
@param DashboardListFilters filters
@param java.util.function.Supplier<java.util.List<com.workin.backend.platformadmin.org.ActiveCompanies.CompanyOption>> companyFilterOptions
@param java.util.function.Supplier<com.workin.backend.platformadmin.web.OrgFilterCascade> orgFilterCascade
@param java.util.function.Supplier<PhoneCountryChoices> phoneCountries
@param List<Employee.Option> branchOptions
@param List<Employee.Option> departmentOptions
@param List<Employee.Option> jobTitleOptions
@param List<Employee.Option> shiftOptions
@param List<ActiveCompanies.CompanyOption> companyOptions
@param String branchesByCompany
@param String departmentsByCompany
@param String departmentsByBranch
@param String jobTitlesByDepartment
@param String jobTitlesByCompany
@param String shiftsByCompany
@param String selectedJobLabel
@param String status
@param long filterBranch
@param long filterDepartment
@param long filterJobTitle
@param String dateFrom
@param String dateTo
@param boolean showCompanyColumn
@param boolean canManage
@param boolean actionsEnabled
@param Employee.Form editRow = null
@param boolean addOpen = false
@param String errorKey = null
@param String currentPage
@param String lang
@param DashboardSession session
@param Function<String, String> t
@param String flash = null
@param String flashType = "success"
@param Function<String, Boolean> available
<%-- Today as legacy reckons it, supplied by AdminViewModelAdvice: the JVM
     default zone is not the one CURDATE() runs in, so a tenure computed here
     would tick over up to three hours before the database agrees. One value
     for the page, so it cannot disagree with itself across midnight. --%>
@param java.time.LocalDate today
!{boolean canWrite = canManage && actionsEnabled;}
!{boolean arabic = "ar".equals(lang);}
!{int colCount = showCompanyColumn ? 14 : 13;}
<%-- Read only when the form below renders; the list alone takes no phone. --%>
!{var phone = addOpen || editRow != null ? phoneCountries.get() : PhoneCountryChoices.NONE;}
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
	flash = flash,
	flashType = flashType,
	available = available,
	pageStyles = java.util.List.of("hr-pages.css", "company-add.css", "org-form.css", "employee-form.css"),
	pageScripts = java.util.List.of("employee-form.js", "employee-shift.js"),
	phoneCountryRules = phone.rules(),
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
			      data-selected-branch="${filterBranch}"
			      data-selected-department="${filterDepartment}"
			      data-selected-job-title="${filterJobTitle}">
				@if(!session.isScopedToOneCompany())
					@template.admin.companyFilterField(companyId = filters.companyId(), companies = companyFilterOptions, t = t)
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
					<select id="emp_branch_f" name="filter_branch" data-filter-branch>
						<option value="0">${t.apply("filter_all")}</option>
						@for(Employee.Option option : branchOptions)
							<option value="${option.id()}" selected="${filterBranch == option.id()}">${option.label()}</option>
						@endfor
					</select>
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="emp_dept_f">${t.apply("department")}</label>
					<select id="emp_dept_f" name="filter_department" data-filter-department>
						<option value="0">${t.apply("filter_all")}</option>
						@for(Employee.Option option : departmentOptions)
							<option value="${option.id()}" selected="${filterDepartment == option.id()}">${option.label()}</option>
						@endfor
					</select>
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="emp_job_f">${t.apply("job_title")}</label>
					<select id="emp_job_f" name="filter_job_title" data-filter-job-title>
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
			<%-- page.php renders this in modal modal--employee-form over a
			     modal-bg open. The port had it as a data-table-card in the
			     page flow, wrapped in account-form org-form -- classes no
			     stylesheet defines here or in legacy. --%>
			<div class="modal-bg open">
				<div class="modal modal--employee-form">
					<%-- page.php:383,398: the window's own close, back to the list. --%>
					<a href="/admin/employees" class="modal-close"
					   aria-label="${t.apply("close")}">&#215;</a>
					<%-- page.php:399: "Edit employee: name", the name through
					     dashboard_employee_display_name(), so an em dash when it is blank. --%>
					<h2>${editRow == null ? t.apply("add_employee")
							: t.apply("edit_employee") + ": " + editRow.displayName()}</h2>
					<%-- _employee_form.php: employee-form.js, copied from legacy, fills the
					     branch, department and job title selects from these maps, which carry
					     only the companies this form may use (R-051); employee-shift.js fills
					     the shift select when the form names its company (D-250).

					     org-form-grid-wrap and the four emp-form-section blocks are legacy's
					     own (_employee_form.php:54-287): employee-form.css lays the sections'
					     rows out two across and collapses them to one on a phone. --%>
					<form method="POST" class="org-form-grid-wrap" data-employee-form
					      data-branches-by-company="${branchesByCompany}"
					      data-departments-by-company="${departmentsByCompany}"
					      data-departments-by-branch="${departmentsByBranch}"
					      data-job-titles-by-dept="${jobTitlesByDepartment}"
					      data-job-titles-by-company="${jobTitlesByCompany}"
					      data-shifts-by-company="${shiftsByCompany}"
					      data-select-company-msg="${t.apply("select_company_first_branches")}"
					      data-select-branch-msg="${t.apply("pick_branch")}"
					      data-select-dept-msg="${t.apply("pick_department")}"
					      data-invalid-phone-msg="${t.apply("error_invalid_phone")}"
					      data-phone-keep-untouched="${editRow != null}"
					      data-selected-company="${editRow == null ? filters.companyId() : editRow.companyId()}"
					      data-selected-branch="${editRow == null ? 0L : editRow.branchId()}"
					      data-selected-department="${editRow == null ? 0L : editRow.departmentId()}"
					      data-selected-job="${editRow == null ? 0L : editRow.jobTitleId()}"
					      data-selected-job-label="${selectedJobLabel}">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="${editRow == null ? "add_employee" : "save_edit"}">
					@if(editRow != null)
						<input type="hidden" name="id" value="${editRow.id()}">
					@elseif(filters.companyId() > 0)
						<%-- Create path only. An edit takes the company from the
						     row itself: D-176. --%>
						<input type="hidden" name="company_id" value="${filters.companyId()}">
					@else
						<%-- With no company to add under, _employee_form.php asks for one
						     rather than posting 0, which the service refuses.
						     employee-form.js reads it as #emp_company. --%>
						<section class="emp-form-section org-form-span-2">
							<div class="form-row">
								<label for="emp_company">${t.apply("company")} *</label>
								<select name="company_id" id="emp_company" required>
									<option value="">${t.apply("search")}...</option>
									@for(ActiveCompanies.CompanyOption company : companyOptions)
										<option value="${company.id()}">${company.name()}</option>
									@endfor
								</select>
							</div>
						</section>
					@endif

					<%-- save_edit writes these columns from the post, so each input
					     carries its stored value: one left empty is one the save
					     clears. Only the password is left blank, and a blank one
					     keeps the credential. --%>
					<section class="emp-form-section org-form-span-2">
						<h3 class="emp-form-section__title">${t.apply("personal_data")}</h3>
						<div class="org-form-grid">
							<div class="form-row">
								<label for="first_name">${t.apply("first_name")} *</label>
								<input type="text" id="first_name" name="first_name" required maxlength="255"
								       value="${editRow == null ? "" : editRow.firstName()}">
							</div>
							<div class="form-row">
								<%-- Not required here, though legacy's input is: legacy's server
								     checks the first name only, and its own rows -- a join request
								     stores one name (R-019) -- would be unsaveable through a form
								     that insisted on a last one. --%>
								<label for="last_name">${t.apply("last_name")}</label>
								<input type="text" id="last_name" name="last_name" maxlength="255"
								       value="${editRow == null ? "" : editRow.lastName()}">
							</div>
							<div class="form-row org-form-span-2">
								<%-- The save validates only a replaced code, so the input asks for
								     one only when the employee has one, and for digits only when
								     the stored code is digits: a pattern the stored value fails
								     would stop a browser submitting a form nobody changed. --%>
								!{boolean codeRequired = editRow == null || !editRow.employeeCode().isBlank();}
								!{boolean codeIsDigits = editRow == null || editRow.employeeCode().isBlank()
										|| editRow.employeeCode().matches("[0-9]+");}
								<label for="emp_code">${t.apply("emp_code")}${codeRequired ? " *" : ""}</label>
								<input type="text" id="emp_code" name="employee_code" maxlength="64"
								       required="${codeRequired}"
								       inputmode="numeric" pattern="${codeIsDigits ? "[0-9]+" : null}" autocomplete="off"
								       value="${editRow == null ? "" : editRow.employeeCode()}">
							</div>
							<div class="form-row org-form-span-2 emp-login-credentials">
								<p class="form-hint emp-login-credentials__hint">${t.apply("employee_mobile_login_hint")}</p>
								<div class="emp-login-credentials__grid">
									<div class="emp-login-credentials__country">
										<%-- Before the phone, as _employee_form.php has it. Required
										     only when a phone is given, and checked before the number
										     itself is judged. A stored code no active country has is
										     listed too, labelled as phone_country_option_label() labels
										     an unknown code, so an unchanged save keeps it: legacy's
										     select drops it. --%>
										<label for="country_code">${t.apply("country_code")}</label>
										<select id="country_code" name="country_code">
											<option value="">${t.apply("optional")}</option>
											@if(editRow != null && editRow.countryCode() != null && !editRow.countryCode().isEmpty() && !phone.offers(editRow.countryCode()))
												<option value="${editRow.countryCode()}" selected>${editRow.countryCode()}</option>
											@endif
											@for(PhoneCountryChoices.Option country : phone.options())
												<option value="${country.code()}"
												        selected="${editRow != null && country.code().equals(editRow.countryCode())}">${country.label()}</option>
											@endfor
										</select>
									</div>
									<div class="emp-login-credentials__phone">
										<label for="phone">${t.apply("phone")}</label>
										<input type="text" id="phone" name="phone" inputmode="tel" autocomplete="off"
										       value="${editRow == null ? "" : editRow.phone()}">
									</div>
									<div class="emp-login-credentials__password">
										<label for="password">${t.apply("password")}</label>
										<input type="password" id="password" name="password" autocomplete="new-password"
										       minlength="6"
										       placeholder="${t.apply(editRow == null ? "password_field_hint" : "password_field_hint_optional")}">
										@if(editRow != null)
											<span class="form-hint">${t.apply("password_optional")}</span>
										@endif
									</div>
								</div>
							</div>
							<div class="form-row">
								<%-- Required on the create path, and the only foreign key
								     legacy insists on there. --%>
								<label for="shift_id">${t.apply("shift")} *</label>
								<%-- An add's empty choice is legacy's value="", which required
								     refuses, so a browser keeps an add with no shift, and what was
								     typed, instead of posting a 0 the service refuses. An edit keeps
								     0: an employee with no shift must still save unchanged. --%>
								<select id="shift_id" name="shift_id" required data-emp-shift>
									<option value="${editRow == null ? "" : "0"}">—</option>
									@for(Employee.Option option : shiftOptions)
										<option value="${option.id()}"
										        selected="${editRow != null && editRow.shiftId() == option.id()}">${option.label()}</option>
									@endfor
								</select>
							</div>
							<div class="form-row">
								<%-- The current assignment's own date: the same shift from
								     the same date is what makes a save add no assignment. --%>
								<label for="shift_effective_from">${t.apply("shift_effective_from")}</label>
								<input type="date" id="shift_effective_from" name="shift_effective_from"
								       value="${editRow == null ? "" : editRow.shiftEffectiveFromInput()}">
							</div>
							<div class="form-row">
								<label for="national_id">${t.apply("national_id")}</label>
								<input type="text" id="national_id" name="national_id" maxlength="64"
								       value="${editRow == null ? "" : editRow.nationalId()}">
							</div>
							<div class="form-row">
								<%-- Each date input shows only a date it can hold. A stored
								     0000-00-00 renders empty, and left empty the save keeps it. --%>
								<label for="birth_date">${t.apply("birth_date")}</label>
								<input type="date" id="birth_date" name="birth_date"
								       value="${editRow == null ? "" : editRow.birthDateInput()}">
							</div>
							<div class="form-row">
								<label for="gender">${t.apply("gender")}</label>
								<select id="gender" name="gender">
									<option value="">${t.apply("optional")}</option>
									<option value="male"
									        selected="${editRow != null && "male".equals(editRow.gender())}">${t.apply("gender_male")}</option>
									<option value="female"
									        selected="${editRow != null && "female".equals(editRow.gender())}">${t.apply("gender_female")}</option>
									@if(editRow != null && "other".equals(editRow.gender()))
										<%-- The column allows it and legacy's select does not
										     offer it, so legacy's own form clears it on save.
										     Offered only to the employee who already has it. --%>
										<option value="other" selected>${t.apply("gender_other")}</option>
									@endif
								</select>
							</div>
							<div class="form-row org-form-span-2">
								<label for="address">${t.apply("address")}</label>
								<input type="text" id="address" name="address" maxlength="500"
								       value="${editRow == null ? "" : editRow.address()}">
							</div>
						</div>
					</section>

					<section class="emp-form-section org-form-span-2">
						<h3 class="emp-form-section__title">${t.apply("job_details")}</h3>
						<div class="org-form-grid">
							<div class="form-row org-form-span-2">
								<%-- The stored date or nothing. Legacy fills a missing one
								     with today and the port filled it with created_at: both
								     write a hire date on a save that changed nothing. --%>
								<label for="hire_date">${t.apply("hire_date")}</label>
								<input type="date" id="hire_date" name="hire_date"
								       value="${editRow == null ? "" : editRow.hireDateInput()}">
							</div>
							<div class="form-row">
								<%-- Legacy labels this one optional and hints that an employee
								     with no branch may check in at any of the company's. Neither
								     is true here: the column is NOT NULL and the save refuses a
								     missing branch (R-055), where legacy's writes NULL. --%>
								<label for="branch_id">${t.apply("branch")}</label>
								<select id="branch_id" name="branch_id" data-emp-branch>
									<option value="0">—</option>
									@for(Employee.Option option : branchOptions)
										<option value="${option.id()}"
										        selected="${editRow != null && editRow.branchId() == option.id()}">${option.label()}</option>
									@endfor
								</select>
							</div>
							<div class="form-row">
								<label for="department_id">${t.apply("department")}</label>
								<select id="department_id" name="department_id" data-emp-department>
									<option value="0">—</option>
									@for(Employee.Option option : departmentOptions)
										<option value="${option.id()}"
										        selected="${editRow != null && editRow.departmentId() == option.id()}">${option.label()}</option>
									@endfor
								</select>
							</div>
							<div class="form-row org-form-span-2">
								<label for="job_title_id">${t.apply("job_title")}</label>
								<select id="job_title_id" name="job_title_id" data-emp-job>
									<option value="0">—</option>
									@for(Employee.Option option : jobTitleOptions)
										<option value="${option.id()}"
										        selected="${editRow != null && editRow.jobTitleId() == option.id()}">${option.label()}</option>
									@endfor
								</select>
							</div>
							<div class="form-row">
								<label for="contract_duration">${t.apply("contract_duration")}</label>
								<input type="number" id="contract_duration" name="contract_duration" min="1" max="60"
								       value="${editRow == null ? "" : editRow.contractValue()}">
							</div>
							<div class="form-row">
								<label for="contract_duration_unit">${t.apply("contract_duration_unit")}</label>
								<select id="contract_duration_unit" name="contract_duration_unit">
									<option value="months">${t.apply("months")}</option>
									<option value="years"
									        selected="${editRow != null && "years".equals(editRow.contractUnit())}">${t.apply("years")}</option>
								</select>
							</div>
						</div>
					</section>

					<section class="emp-form-section org-form-span-2">
						<h3 class="emp-form-section__title">${t.apply("mobile_attendance")}</h3>
						<div class="org-form-grid">
							<div class="form-row org-form-span-2">
								<%-- Ticked for a new employee, as legacy's is. Unticked
								     posts nothing, and nothing saves as off. --%>
								<label class="emp-checkbox-label">
									<input type="checkbox" name="is_mobile_attendance_enabled" value="1"
									       checked="${editRow == null || editRow.mobileAttendance()}">
									${t.apply("mobile_attendance_enabled")}
								</label>
							</div>
						</div>
					</section>

					@if(editRow == null)
						<%-- Create path only: legacy's edit never touches the salary
						     contract (_employee_form.php:231-283). A contract row is
						     written only for a basic salary above zero, and it carries
						     all ten components as posted. --%>
						<section class="emp-form-section org-form-span-2">
							<h3 class="emp-form-section__title">${t.apply("salary_data")}</h3>
							<div class="org-form-grid emp-salary-grid">
								<div>
									<h4 class="emp-form-subtitle">${t.apply("entitlements")}</h4>
									<div class="form-row">
										<label for="basic_salary">${t.apply("basic_salary")} (${t.apply("mandatory")})</label>
										<input type="number" id="basic_salary" name="basic_salary" step="0.01" min="0" value="0" required>
									</div>
									<div class="form-row">
										<label for="transport">${t.apply("transport_allowance")} (${t.apply("optional")})</label>
										<input type="number" id="transport" name="transport" step="0.01" min="0" value="0">
									</div>
									<div class="form-row">
										<label for="food">${t.apply("food_allowance")} (${t.apply("optional")})</label>
										<input type="number" id="food" name="food" step="0.01" min="0" value="0">
									</div>
									<div class="form-row">
										<label for="risk">${t.apply("risk_allowance")} (${t.apply("optional")})</label>
										<input type="number" id="risk" name="risk" step="0.01" min="0" value="0">
									</div>
									<div class="form-row">
										<label for="incentives">${t.apply("incentives")} (${t.apply("optional")})</label>
										<input type="number" id="incentives" name="incentives" step="0.01" min="0" value="0">
									</div>
								</div>
								<div>
									<h4 class="emp-form-subtitle">${t.apply("deductions")}</h4>
									<div class="form-row">
										<label for="insurance">${t.apply("insurance_deduction")} (${t.apply("optional")})</label>
										<input type="number" id="insurance" name="insurance" step="0.01" min="0" value="0">
									</div>
									<div class="form-row">
										<label for="tax">${t.apply("tax_deduction")} (${t.apply("optional")})</label>
										<input type="number" id="tax" name="tax" step="0.01" min="0" value="0">
									</div>
									<div class="form-row">
										<label for="advances">${t.apply("advances_deduction")} (${t.apply("optional")})</label>
										<input type="number" id="advances" name="advances" step="0.01" min="0" value="0">
									</div>
									<div class="form-row">
										<label for="fund">${t.apply("fund_deduction")} (${t.apply("optional")})</label>
										<input type="number" id="fund" name="fund" step="0.01" min="0" value="0">
									</div>
									<div class="form-row">
										<label for="penalty">${t.apply("penalty_deduction")} (${t.apply("optional")})</label>
										<input type="number" id="penalty" name="penalty" step="0.01" min="0" value="0">
									</div>
								</div>
							</div>
						</section>
					@endif
					<div class="form-footer org-form-span-2">
						<a href="/admin/employees" class="btn btn-gray">${t.apply("cancel")}</a>
						@if(canWrite)
							<%-- _employee_form.php:286: the add window's button says what it
							     adds, and the edit window's says Save. --%>
							<button type="submit" class="btn btn-blue">${editRow == null
									? t.apply("add_employee") : t.apply("save")}</button>
						@endif
					</div>
					</form>
				</div>
			</div>
		@endif

		<div class="data-table-card">
			<div class="data-table-head">
				<h2 class="data-table-title">${t.apply("employees")} (${result.total()})</h2>
				@if(canManage)
					<a href="/admin/employees?action=add" class="btn btn-blue">+ ${t.apply("add_employee")}</a>
				@endif
			</div>
			<div class="table-wrap">
			<table class="tbl">
				<thead>
					<%-- The columns legacy renders, in legacy's order. Six of them
					     were selected by the store and shown by nothing: the avatar,
					     the basic salary, the hire and created dates, the length of
					     service and the contract duration. Code lives under the name
					     and department/job title under the branch, as two-line cells,
					     which is how the page fits fourteen columns. --%>
					<tr>
						<th>#</th>
						<th>${t.apply("employee_logo")}</th>
						<th>${t.apply("employee_name")}</th>
						<th>${t.apply("phone")}</th>
						@if(showCompanyColumn)<th>${t.apply("company")}</th>@endif
						<th>${t.apply("branch")}</th>
						<th>${t.apply("shift")}</th>
						<th class="col-center">${t.apply("basic_salary")}</th>
						<th>${t.apply("hire_date")}</th>
						<th>${t.apply("created_at")}</th>
						<th>${t.apply("work_tenure")}</th>
						<th>${t.apply("contract_duration")}</th>
						<th>${t.apply("status")}</th>
						@template.admin.actionsHeader(t = t)
					</tr>
				</thead>
				<tbody>
					@for(int index = 0; index < result.data().size(); index++)
						!{Employee row = result.data().get(index);}
						<tr>
							<td class="text-muted">${result.from() + index}</td>
							<td>
								@if(row.photoUrl() == null || row.photoUrl().isBlank())
									<span class="emp-tbl-avatar" aria-hidden="true">${EmployeeDisplay.initials(EmployeeDisplay.displayName(row.employeeName(), "E"))}</span>
								@else
									<img src="${row.photoUrl()}" alt="" class="emp-tbl-photo"
									     width="40" height="40" loading="lazy"
									     data-fallback-initials="${EmployeeDisplay.initials(EmployeeDisplay.displayName(row.employeeName(), "E"))}">
								@endif
							</td>
							<td>
								<div class="bold">${EmployeeDisplay.displayName(row.employeeName(), "—")}</div>
								<div class="tbl-sub">${row.employeeCode() == null || row.employeeCode().isBlank()
									? "—" : row.employeeCode()}</div>
							</td>
							<td class="nowrap text-muted" dir="ltr">${row.phoneLabel()}</td>
							@if(showCompanyColumn)
								<td class="text-muted">${row.companyName() == null ? "—" : row.companyName()}</td>
							@endif
							<td>
								<div class="bold">${row.branchLabel()}</div>
								<div class="tbl-sub">${row.departmentLabel()}${
									"—".equals(row.departmentLabel()) || "—".equals(row.jobTitleLabel())
										? "" : " · "}${"—".equals(row.jobTitleLabel()) ? "" : row.jobTitleLabel()}</div>
							</td>
							<td class="text-muted">${row.shiftLabel()}</td>
							<td class="col-center" dir="ltr">${EmployeeDisplay.basicSalary(row.basicSalary())}</td>
							<td class="nowrap text-muted">${EmployeeDisplay.date(row.hireDate())}</td>
							<td class="nowrap text-muted">${EmployeeDisplay.date(row.createdAt())}</td>
							<td class="nowrap text-muted">${EmployeeDisplay.workTenure(row.hireDate(), arabic, today)}</td>
							<td class="text-muted">${EmployeeDisplay.contractDuration(row.contractDurationMonths(), arabic)}</td>
							<td>
								@template.admin.statusBadge(status = row.active() ? "active" : "suspended", t = t)
							</td>
							<td class="col-actions">
								@if(canManage)
									@template.admin.rowActions(rowId = row.id(), t = t, items = @`
										<%-- employee_helper.php:666-667: Details first, then Edit. --%>
										<a href="/admin/employee_detail?id=${row.id()}"
										   role="menuitem" class="row-actions__item">${t.apply("details")}</a>
										<a href="/admin/employees?action=edit&id=${row.id()}"
										   role="menuitem" class="row-actions__item">${t.apply("edit")}</a>
										@if(canWrite)
											<%-- employee_helper.php:671-686: deactivating is a danger
											     item behind confirm_delete, legacy's own prompt for it;
											     reactivating is a success item and asks nothing. --%>
											@if(row.active())
												<form method="POST" class="row-actions__form" role="none"
												      onsubmit="return confirm('${t.apply("confirm_delete")}')">
													<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
													<input type="hidden" name="action" value="deactivate">
													<input type="hidden" name="id" value="${row.id()}">
													<button type="submit" role="menuitem"
													        class="row-actions__item row-actions__item--danger">${t.apply("deactivate")}</button>
												</form>
											@else
												<form method="POST" class="row-actions__form" role="none">
													<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
													<input type="hidden" name="action" value="reactivate">
													<input type="hidden" name="id" value="${row.id()}">
													<button type="submit" role="menuitem"
													        class="row-actions__item row-actions__item--success">${t.apply("reactivate")}</button>
												</form>
											@endif
											<form method="POST" class="row-actions__form" role="none"
											      onsubmit="return confirm('${t.apply("confirm_delete")}')">
												<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
												<input type="hidden" name="action" value="delete">
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
						<tr><td colspan="${colCount}" class="data-table-empty">${t.apply("no_data")}</td></tr>
					@endif
				</tbody>
			</table>
			</div>
			<%-- Plus this page's own three. DashboardListFilters carries branch
			     and department because four pages share them; the job title and
			     the hire-date range are employees' alone, and the size selector
			     dropped them. --%>
			!{java.util.SequencedMap<String, String> carry =
					new java.util.LinkedHashMap<>(filters.asQueryParameters());}
			!{if (filterJobTitle > 0) { carry.put("filter_job_title", String.valueOf(filterJobTitle)); }}
			!{if (!dateFrom.isEmpty()) { carry.put("date_from", dateFrom); }}
			!{if (!dateTo.isEmpty()) { carry.put("date_to", dateTo); }}
		</div>
		@template.admin.pager(basePath = "/admin/employees", result = result,
				carry = carry, t = t)
	`)
