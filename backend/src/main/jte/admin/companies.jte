@import java.util.List
@import com.workin.backend.platformadmin.PlatformAdminCompanyDirectory.CompanyView
@import com.workin.backend.platformadmin.companies.CompanyListFilters
@import com.workin.backend.platformadmin.companies.CompanyRow
@import com.workin.backend.platformadmin.hr.EmployeeDisplay
@import com.workin.backend.platformadmin.web.DashboardPage
@import java.util.function.Function
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param boolean factorBound = false
@param boolean actionsEnabled
@param List<CompanyView> companies
@param String error = null
@param String currentPage
@param String lang
@param com.workin.backend.platformadmin.web.DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
<%-- The directory, present only under phase1-mysql. The three lookup tables
     and the employee/branch/department counts are legacy schema, and
     PlatformAdminCompanyDirectory -- which ADR-0015 keeps narrow because it is
     the interface the lifecycle actions run through -- has no answer for them
     on PostgreSQL. So that profile keeps the short list it already had rather
     than being owed ten columns it cannot fill. --%>
@param DashboardPage<CompanyRow> result = null
@param CompanyListFilters filters = null
@param List<CompanyRow.Option> activities = java.util.List.of()
@param List<CompanyRow.Option> titles = java.util.List.of()
@param List<CompanyRow.Option> sizes = java.util.List.of()
<%-- dashboard/pages/companies/page.php. --%>
@template.admin.layout(
	title = t.apply("nav_companies"),
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentAdminPhone = currentAdminPhone,
	factorBound = factorBound,
	currentPage = currentPage,
	lang = lang,
	session = session,
	t = t,
	available = available,
	pageStyles = java.util.List.of("hr-pages.css", "company-add.css"),
	body = @`
		@if(error != null)
			<div class="flash flash-error">${error}</div>
		@endif
		@if(!actionsEnabled)
			<div class="flash flash-warning">${t.apply("admin_actions_disabled")}</div>
		@endif

		@if(result == null)
			<div class="data-table-card">
				<div class="data-table-head">
					<h2 class="data-table-title">${t.apply("nav_companies")} (${companies.size()})</h2>
				</div>
				<div class="table-wrap">
					<table class="tbl">
						<thead>
							<tr>
								<th>#</th>
								<th>${t.apply("company_name")}</th>
								<th>${t.apply("status")}</th>
								<th class="col-actions"><span class="sr-only">${t.apply("actions")}</span></th>
							</tr>
						</thead>
						<tbody>
							@for(CompanyView company : companies)
								<tr>
									<td class="text-muted">${company.id()}</td>
									<td class="bold">${company.name() == null ? "—" : company.name()}</td>
									<td>
										<span class="badge ${"active".equals(company.status()) ? "badge-green"
												: "pending".equals(company.status()) ? "badge-yellow"
												: "rejected".equals(company.status()) ? "badge-red" : "badge-gray"}">
											${t.apply("status_" + company.status())}
										</span>
									</td>
									<td class="col-actions">
										@template.admin.companyActions(companyId = company.id(),
												status = company.status(), actionsEnabled = actionsEnabled,
												csrfParameterName = csrfParameterName, csrfToken = csrfToken, t = t)
									</td>
								</tr>
							@endfor
							@if(companies.isEmpty())
								<tr><td colspan="4" class="data-table-empty">${t.apply("no_data")}</td></tr>
							@endif
						</tbody>
					</table>
				</div>
			</div>
		@else
			<div class="toolbar page-toolbar page-toolbar-filters">
				<form method="GET" class="toolbar-form toolbar-form--labeled">
					<div class="filter-field">
						<label class="filter-field__label" for="co_search">${t.apply("company_name")}</label>
						<input type="search" id="co_search" name="search"
						       placeholder="${t.apply("search")}..." value="${filters.search()}">
					</div>
					<div class="filter-field">
						<label class="filter-field__label" for="co_filter">${t.apply("status")}</label>
						<select name="filter" id="co_filter">
							<option value="all" selected="${"all".equals(filters.status())}">${t.apply("filter_all")}</option>
							<option value="active" selected="${"active".equals(filters.status())}">${t.apply("status_active")}</option>
							<option value="pending" selected="${"pending".equals(filters.status())}">${t.apply("status_pending")}</option>
							<option value="rejected" selected="${"rejected".equals(filters.status())}">${t.apply("status_rejected")}</option>
						</select>
					</div>
					<div class="filter-field">
						<label class="filter-field__label" for="co_activity">${t.apply("company_activity")}</label>
						<select name="filter_activity" id="co_activity">
							<option value="0">${t.apply("filter_all")}</option>
							@for(CompanyRow.Option option : activities)
								<option value="${option.id()}"
								        selected="${filters.activityId() == option.id()}">${option.name()}</option>
							@endfor
						</select>
					</div>
					<div class="filter-field">
						<label class="filter-field__label" for="co_title">${t.apply("company_title")}</label>
						<select name="filter_title" id="co_title">
							<option value="0">${t.apply("filter_all")}</option>
							@for(CompanyRow.Option option : titles)
								<option value="${option.id()}"
								        selected="${filters.titleId() == option.id()}">${option.name()}</option>
							@endfor
						</select>
					</div>
					<div class="filter-field">
						<label class="filter-field__label" for="co_size">${t.apply("company_size")}</label>
						<select name="filter_size" id="co_size">
							<option value="0">${t.apply("filter_all")}</option>
							@for(CompanyRow.Option option : sizes)
								<option value="${option.id()}"
								        selected="${filters.sizeId() == option.id()}">${option.name()}</option>
							@endfor
						</select>
					</div>
					<button type="submit" class="btn btn-blue">${t.apply("search")}</button>
				</form>
			</div>

			<div class="data-table-card">
				<div class="data-table-head">
					<h2 class="data-table-title">${t.apply("nav_companies")} (${result.total()})</h2>
				</div>
				<div class="table-wrap">
					<table class="tbl">
						<thead>
							<tr>
								<th>#</th>
								<th>${t.apply("company_logo")}</th>
								<th>${t.apply("company_name")}</th>
								<th>${t.apply("account_holder")}</th>
								<th>${t.apply("phone")}</th>
								<th>${t.apply("company_activity")}</th>
								<th>${t.apply("company_title")}</th>
								<th>${t.apply("company_size")}</th>
								<th>${t.apply("status")}</th>
								<th class="col-center">${t.apply("employees")}</th>
								<th class="col-center">${t.apply("branches")}</th>
								<th class="col-center">${t.apply("departments")}</th>
								<th>${t.apply("reg_date")}</th>
								<th class="col-actions"><span class="sr-only">${t.apply("actions")}</span></th>
							</tr>
						</thead>
						<tbody>
							@for(int index = 0; index < result.data().size(); index++)
								!{CompanyRow row = result.data().get(index);}
								<tr>
									<td class="text-muted">${result.from() + index}</td>
									<td>
										<%-- Legacy falls back to ui-avatars.com here. Drawn locally
										     instead, for the reason D-197 self-hosts the typeface: an
										     admin panel should not need a third party to render, and
										     no viewer's IP should reach one to see a placeholder. --%>
										@if(row.hasLogo())
											<img src="${row.logoUrl()}" alt="" class="tbl-photo company-tbl-logo"
											     width="40" height="40" loading="lazy"
											     data-fallback-initials="${EmployeeDisplay.initials(row.nameLabel())}">
										@else
											<span class="emp-tbl-avatar" aria-hidden="true">${EmployeeDisplay.initials(row.nameLabel())}</span>
										@endif
									</td>
									<td>
										<div class="bold">${row.nameLabel()}</div>
										@if(row.hasCommercialReg())
											<a href="${row.commercialRegUrl()}" target="_blank" rel="noopener"
											   class="tbl-sub">${t.apply("commercial_reg")}</a>
										@endif
									</td>
									<td>${row.accountHolder()}</td>
									<td class="text-muted" dir="ltr">${row.phone()}</td>
									<td class="text-muted">${row.activityLabel()}</td>
									<td class="text-muted">${row.titleLabel()}</td>
									<%-- A headcount band -- "500 - 1000" -- reads back to front
									     in a right-to-left cell, which turns the range inside out. --%>
									<td class="text-muted" dir="ltr">${row.sizeLabel()}</td>
									<td>
										<span class="badge ${"active".equals(row.status()) ? "badge-green"
												: "pending".equals(row.status()) ? "badge-yellow"
												: "rejected".equals(row.status()) ? "badge-red" : "badge-gray"}">
											${t.apply("status_" + row.status())}</span>
									</td>
									<td class="col-center"><span class="badge badge-blue">${row.employeeCount()}</span></td>
									<td class="col-center"><span class="badge badge-gray">${row.branchCount()}</span></td>
									<td class="col-center"><span class="badge badge-gray">${row.departmentCount()}</span></td>
									<td class="text-muted">${row.createdDate()}</td>
									<td class="col-actions">
										@template.admin.companyActions(companyId = row.id(),
												status = row.status(), actionsEnabled = actionsEnabled,
												csrfParameterName = csrfParameterName, csrfToken = csrfToken, t = t)
									</td>
								</tr>
							@endfor
							@if(result.isEmpty())
								<tr><td colspan="14" class="data-table-empty">${t.apply("no_data")}</td></tr>
							@endif
						</tbody>
					</table>
				</div>
				@template.admin.pager(basePath = "/admin/companies", result = result,
						carry = filters.asQueryParameters(), t = t)
			</div>
		@endif
	`)
