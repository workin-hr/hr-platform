@import com.workin.backend.platformadmin.PlatformAdminCompanyDirectory.EditableCompany
@import com.workin.backend.platformadmin.companies.CompanyListFilters
@import com.workin.backend.platformadmin.companies.CompanyRow
@import com.workin.backend.platformadmin.hr.EmployeeDisplay
@import com.workin.backend.platformadmin.web.DashboardPage
@import com.workin.backend.platformadmin.web.DashboardSession
@import java.util.List
@import java.util.function.Function
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param boolean actionsEnabled
@param String errorKey = null
@param String currentPage
@param String lang
@param DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
<%-- The directory: the dashboard's fourteen columns, its five filters and
     its pager. Always supplied -- the controller has one store to read. --%>
@param DashboardPage<CompanyRow> result
@param CompanyListFilters filters
@param List<CompanyRow.Option> activities = java.util.List.of()
@param List<CompanyRow.Option> titles = java.util.List.of()
@param List<CompanyRow.Option> sizes = java.util.List.of()
<%-- ?action=add or ?edit=<id>, and the row being edited when there is one. --%>
@param boolean formOpen = false
@param long editCompanyId = 0
@param EditableCompany editCompany = null
<%-- dashboard/pages/companies/page.php. --%>
@template.admin.layout(
	title = t.apply("nav_companies"),
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentAdminPhone = currentAdminPhone,
	currentPage = currentPage,
	lang = lang,
	session = session,
	t = t,
	available = available,
	pageStyles = java.util.List.of("hr-pages.css", "company-add.css"),
	body = @`
		@if(errorKey != null)
			<div class="flash flash-error">${t.apply(errorKey)}</div>
		@endif
		@if(!actionsEnabled)
			<div class="flash flash-warning">${t.apply("admin_actions_disabled")}</div>
		@endif

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
					<div class="data-table-head__actions">
						@if(actionsEnabled)
							<%-- page.php's own entry point to _company_form.php. --%>
							<button type="button" class="btn btn-blue btn-sm"
									onclick="crudOpenAdd('companyModal')">+ ${t.apply("add_company")}</button>
						@endif
					</div>
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
		@if(actionsEnabled)
			<%-- A rejection must say why. The other actions post from the menu
			     directly; this one needs a box, and one dialog serves every row. --%>
			@template.admin.rowDialog(id = "company-reject", action = "COMPANY_REJECT",
					formAction = "/admin/companies/action",
					title = t.apply("reject"), csrfParameterName = csrfParameterName,
					csrfToken = csrfToken, t = t, submitLabel = t.apply("reject"),
					submitVariant = "danger",
					fields = @`
						<input type="hidden" name="companyId" data-dialog-field="companyId">
						<label>${t.apply("rejection_reason")}
							<textarea name="reason" rows="3" required></textarea></label>
					`)

			<%-- _company_form.php, serving add and edit as it does. Opened by the
			     "+" button or by ?edit=<id>; page.php keeps it in a modal and so
			     does this. The logo is required to add and optional to edit,
			     because company_admin_update falls back to the stored one. --%>
			<div class="modal-bg${formOpen ? " open" : ""}" id="companyModal">
				<div class="modal modal--company-add" role="dialog" aria-modal="true"
						aria-labelledby="companyModal-title">
					<button type="button" class="modal-close"
							aria-label="${t.apply("cancel")}">&times;</button>
					<h2 id="companyModal-title">${t.apply(editCompanyId > 0 ? "edit" : "add_company")}</h2>
					<form method="POST" action="/admin/companies/save"
							enctype="multipart/form-data">
						<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
						<input type="hidden" name="action"
						       value="${editCompanyId > 0 ? "save_edit" : "add"}">
						<input type="hidden" name="id" value="${editCompanyId}">
						<div class="company-add-grid">
							<div class="form-row company-add-span-2">
								<label for="co_name">${t.apply("company_name")}</label>
								<input type="text" id="co_name" name="company_name" required maxlength="255"
								       value="${editCompany == null ? "" : editCompany.companyName()}">
							</div>
							<div class="form-row">
								<label for="co_first">${t.apply("first_name")}</label>
								<input type="text" id="co_first" name="first_name" required
								       value="${editCompany == null ? "" : editCompany.firstName()}">
							</div>
							<div class="form-row">
								<label for="co_last">${t.apply("last_name")}</label>
								<input type="text" id="co_last" name="last_name" required
								       value="${editCompany == null ? "" : editCompany.lastName()}">
							</div>
							<div class="form-row">
								<label for="co_code">${t.apply("country_code")}</label>
								<input type="text" id="co_code" name="country_code" dir="ltr"
								       value="${editCompany == null ? "+20" : editCompany.countryCode()}">
							</div>
							<div class="form-row">
								<label for="co_phone">${t.apply("phone")}</label>
								<input type="text" id="co_phone" name="phone" dir="ltr" required
								       value="${editCompany == null ? "" : editCompany.phone()}">
							</div>
							<div class="form-row company-add-span-2">
								<%-- Required to add and optional to edit: blank on an edit
								     leaves the stored password alone, which is what
								     company_admin_update does. --%>
								<label for="co_pw">${t.apply("password")}</label>
								<input type="password" id="co_pw" name="password" minlength="6"
								       required="${editCompanyId == 0}">
							</div>
							<div class="form-row company-add-span-2">
								<label for="co_addr">${t.apply("main_branch_address")}</label>
								<input type="text" id="co_addr" name="main_branch_address" required
								       value="${editCompany == null ? "" : editCompany.mainBranchAddress()}">
							</div>
							<div class="form-row">
								<label for="co_act">${t.apply("company_activity")}</label>
								<select id="co_act" name="company_activity_id" required>
									@for(CompanyRow.Option option : activities)
										<option value="${option.id()}"
											selected="${editCompany != null && editCompany.activityId() == option.id()}">${option.name()}</option>
									@endfor
								</select>
							</div>
							<div class="form-row">
								<label for="co_title">${t.apply("company_title")}</label>
								<select id="co_title" name="company_title_id" required>
									@for(CompanyRow.Option option : titles)
										<option value="${option.id()}"
											selected="${editCompany != null && editCompany.titleId() == option.id()}">${option.name()}</option>
									@endfor
								</select>
							</div>
							<div class="form-row">
								<label for="co_size">${t.apply("company_size")}</label>
								<select id="co_size" name="company_size_id" required>
									@for(CompanyRow.Option option : sizes)
										<option value="${option.id()}"
											selected="${editCompany != null && editCompany.sizeId() == option.id()}">${option.name()}</option>
									@endfor
								</select>
							</div>
							<div class="form-row">
								<label for="co_logo">${t.apply("company_logo")}</label>
								<small class="text-muted">${t.apply("current_logo")}</small>
								<input type="file" id="co_logo" name="logo"
								       accept="image/jpeg,image/png,image/webp"
								       required="${editCompanyId == 0}">
							</div>
							<div class="form-row company-add-span-2">
								<label for="co_reg">${t.apply("commercial_reg")}</label>
								<input type="file" id="co_reg" name="commercial_reg"
								       accept="image/jpeg,image/png,image/webp,application/pdf">
							</div>
						</div>
						<div class="form-footer">
							<a href="/admin/companies" class="btn btn-gray">${t.apply("cancel")}</a>
							<button type="submit" class="btn btn-blue">${t.apply("save")}</button>
						</div>
					</form>
				</div>
			</div>
		@endif
	`)
