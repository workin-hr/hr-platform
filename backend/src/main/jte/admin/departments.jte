@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.org.ActiveCompanies
@import com.workin.backend.platformadmin.org.Department
@import com.workin.backend.platformadmin.web.DashboardListFilters
@import com.workin.backend.platformadmin.web.DashboardPage
@import com.workin.backend.platformadmin.web.DashboardSession
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param DashboardPage<Department> result
@param DashboardListFilters filters
@param java.util.function.Supplier<java.util.List<com.workin.backend.platformadmin.org.ActiveCompanies.CompanyOption>> companyFilterOptions
@param java.util.function.Supplier<com.workin.backend.platformadmin.web.OrgFilterCascade> orgFilterCascade
@param List<Department.BranchOption> branchOptions
@param List<Department.BranchOption> formBranches = List.of()
@param boolean pickCompany = false
@param List<ActiveCompanies.CompanyOption> companyOptions = List.of()
@param String branchesByCompany = "{}"
@param String selectedBranches = "[]"
@param boolean showCompanyColumn
@param boolean canManage
@param boolean actionsEnabled
@param Department editRow = null
@param boolean addOpen = false
@param String errorKey = null
@param String currentPage
@param String lang
@param DashboardSession session
@param Function<String, String> t
@param String flash = null
@param String flashType = "success"
@param Function<String, Boolean> available
!{boolean canWrite = canManage && actionsEnabled;}
!{int colCount = showCompanyColumn ? 9 : 8;}
!{long selectedCount = editRow == null ? 0
		: formBranches.stream().filter(option -> editRow.branchIds().contains(option.id())).count();}
<%-- dashboard/pages/departments/page.php. --%>
@template.admin.layout(
	title = t.apply("departments"),
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
	pageStyles = java.util.List.of("hr-pages.css", "org-form.css"),
	pageScripts = java.util.List.of("department-form.js"),
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
			      data-selected-department="0"
			      data-selected-job-title="0">
				@if(!session.isScopedToOneCompany())
					@template.admin.companyFilterField(companyId = filters.companyId(), companies = companyFilterOptions, t = t)
				@endif
				<div class="filter-field">
					<label class="filter-field__label" for="dp_search">${t.apply("department")}</label>
					<input type="search" id="dp_search" name="search"
					       placeholder="${t.apply("search")}..." value="${filters.search()}">
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="dp_branch_f">${t.apply("branch")}</label>
					<select name="filter_branch" id="dp_branch_f" data-filter-branch>
						<option value="0">${t.apply("filter_all")}</option>
						@for(Department.BranchOption option : branchOptions)
							<option value="${option.id()}" selected="${filters.filterBranch() == option.id()}">
								${option.companyName() == null ? option.name()
									: option.companyName() + " — " + option.name()}</option>
						@endfor
					</select>
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="dp_status">${t.apply("status")}</label>
					<select id="dp_status" name="filter">
						<option value="all" selected="${"all".equals(filters.status())}">${t.apply("filter_all")}</option>
						<option value="active" selected="${"active".equals(filters.status())}">${t.apply("status_active")}</option>
						<option value="inactive" selected="${"inactive".equals(filters.status())}">${t.apply("status_inactive")}</option>
					</select>
				</div>
				<button type="submit" class="btn btn-blue">${t.apply("search")}</button>
			</form>
		</div>

		@if(addOpen || editRow != null)
			<%-- page.php renders this over modal-bg open, in
			     modal modal--org-form -- the form panel org-form.css styles.
			     The port had it as a data-table-card in the page flow, and
			     wrapped the form in account-form org-form, which no
			     stylesheet defines here or in legacy. --%>
			<div class="modal-bg open">
				<div class="modal modal--org-form">
					<h2>${editRow == null ? t.apply("add_department")
							: t.apply("edit") + ": " + editRow.name()}</h2>
					<form method="POST">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="${editRow == null ? "add" : "save_edit"}">
					@if(editRow != null)
						<input type="hidden" name="id" value="${editRow.id()}">
						<input type="hidden" name="company_id" value="${editRow.companyId()}">
					@endif

					<%-- The rows are a grid in legacy (_department_form.php), with the wide ones
					     marked "org-form-span-2". Without the wrapper every field is a
					     full-width band. The data-* attributes are what department-form.js
					     reads; unlike legacy there is no data-fixed-company, so an edit or a
					     filtered add keeps the cards rendered here instead of a re-render from
					     active branches only, which would drop a retired branch the department
					     is still linked to. --%>
					<div class="org-form-grid"
					     data-org-dept-form
					     data-branches="${branchesByCompany}"
					     data-selected-branches="${selectedBranches}"
					     data-select-company-msg="${t.apply("select_company_first_branches")}"
					     data-label-select-all="${t.apply("branches_select_all")}"
					     data-label-clear-all="${t.apply("branches_clear_all")}"
					     data-label-search="${t.apply("branches_search")}"
					     data-label-selected-one="${t.apply("branches_selected_label")}"
					     data-label-selected-many="${t.apply("branches_selected_label_plural")}">
					@if(editRow == null)
						@template.admin.companyField(session = session, companyId = filters.companyId(),
								companies = companyOptions, inputId = "dp_company_id", cascade = "dp", t = t)
					@endif
					<div class="form-row org-form-span-2">
						<label for="name">${t.apply("department")}</label>
						<input type="text" id="name" name="name" required maxlength="255"
						       value="${editRow == null ? "" : editRow.name()}">
					</div>
					<div class="form-row org-form-span-2 dept-branches-field">
						<%-- At least one is required, and every one must belong to the
						     department's company. The service checks both; the cards only
						     offer that company's branches. --%>
						<div class="dept-branches-field__label-row">
							<span class="form-label-static">${t.apply("branches")}</span>
							<span class="dept-branches-badge" data-dept-count-badge hidden="${selectedCount == 0}">${selectedCount == 0 ? "0"
								: selectedCount + " " + t.apply(selectedCount == 1 ? "branches_selected_label" : "branches_selected_label_plural")}</span>
						</div>
						<p class="form-hint dept-branches-hint">${t.apply("branches_select_hint")}</p>
						<div class="dept-branches-panel${pickCompany ? " is-disabled" : ""}" data-dept-branches-panel>
							<div class="dept-branches-toolbar" data-dept-toolbar hidden="${pickCompany}">
								<input type="search" class="dept-branches-search" data-dept-search
								       placeholder="${t.apply("branches_search")}" autocomplete="off">
								<div class="dept-branches-toolbar__actions">
									<button type="button" class="btn btn-outline btn-sm" data-dept-select-all>${t.apply("branches_select_all")}</button>
									<button type="button" class="btn btn-outline btn-sm" data-dept-clear-all>${t.apply("branches_clear_all")}</button>
								</div>
							</div>
							<div class="dept-branches-picker" data-dept-branches-picker data-disabled="${pickCompany ? "1" : null}">
								@if(pickCompany)
									<div class="dept-branches-picker__empty">
										<span class="dept-branches-picker__empty-icon" aria-hidden="true">◎</span>
										<p>${t.apply("select_company_first_branches")}</p>
									</div>
								@elseif(formBranches.isEmpty())
									<div class="dept-branches-picker__empty">
										<p>${t.apply("no_data")}</p>
									</div>
								@else
									<div class="dept-branches-grid" data-dept-branches-grid>
										@for(Department.BranchOption option : formBranches)
											!{boolean checked = editRow != null && editRow.branchIds().contains(option.id());}
											<label class="dept-branch-card${checked ? " is-selected" : ""}">
												<input type="checkbox" class="dept-branch-card__input" name="branch_ids[]"
												       value="${option.id()}" checked="${checked}">
												<span class="dept-branch-card__box" aria-hidden="true">
													<svg class="dept-branch-card__icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
														<path d="M5 12l5 5L20 7"/>
													</svg>
												</span>
												<span class="dept-branch-card__name">${option.name()}</span>
											</label>
										@endfor
									</div>
								@endif
							</div>
						</div>
					</div>
					@if(editRow != null)
						<div class="form-row org-form-span-2">
							<label for="is_active">${t.apply("status")}</label>
							<select id="is_active" name="is_active">
								<option value="1" selected="${editRow.active()}">${t.apply("status_active")}</option>
								<option value="0" selected="${!editRow.active()}">${t.apply("status_inactive")}</option>
							</select>
						</div>
					@endif
										</div>

					<div class="form-footer">
						<a href="/admin/departments" class="btn btn-gray">${t.apply("cancel")}</a>
						@if(canWrite)
							<button type="submit" class="btn btn-blue">${t.apply("save")}</button>
						@endif
					</div>
					</form>
				</div>
			</div>
		@endif

		<div class="data-table-card">
			<div class="data-table-head">
				<h2 class="data-table-title">${t.apply("departments")} (${result.total()})</h2>
				@if(canManage)
					<a href="/admin/departments?action=add" class="btn btn-blue">+ ${t.apply("add_department")}</a>
				@endif
			</div>
			<div class="table-wrap">
			<table class="tbl">
				<thead>
					<tr>
						<th>#</th>
						@if(showCompanyColumn)<th>${t.apply("company")}</th>@endif
						<th>${t.apply("department")}</th>
						<th>${t.apply("branches")}</th>
						<th>${t.apply("employees")}</th>
						<th>${t.apply("job_titles")}</th>
						<th>${t.apply("status")}</th>
						<th>${t.apply("reg_date")}</th>
						@template.admin.actionsHeader(t = t)
					</tr>
				</thead>
				<tbody>
					@for(int index = 0; index < result.data().size(); index++)
						!{Department row = result.data().get(index);}
						<tr>
							<td>${result.from() + index}</td>
							@if(showCompanyColumn)
								<td class="text-muted">${row.companyName() == null ? "—" : row.companyName()}</td>
							@endif
							<td class="bold">${row.name()}</td>
							<td class="dept-branches-cell">${row.branchesDisplay()}</td>
							<td><span class="badge badge-blue">${row.employeeCount()}</span></td>
							<td><span class="badge badge-gray">${row.jobTitleCount()}</span></td>
							<td>
								@template.admin.statusBadge(status = row.active() ? "active" : "suspended", t = t)
							</td>
							<td>${row.createdDate()}</td>
							<td class="col-actions">
								@if(canManage)
									@template.admin.rowActions(rowId = row.id(), t = t, items = @`
										<a href="/admin/departments?action=edit&id=${row.id()}"
										   role="menuitem" class="row-actions__item">${t.apply("edit")}</a>
										@if(canWrite)
											<form method="POST" class="row-actions__form" role="none"
											      onsubmit="return confirm('${t.apply("confirm_delete")}')">
												<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
												<input type="hidden" name="action" value="delete">
												<input type="hidden" name="id" value="${row.id()}">
												<input type="hidden" name="company_id" value="${row.companyId()}">
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
		</div>
		@template.admin.pager(basePath = "/admin/departments", result = result,
				carry = filters.asQueryParameters(), t = t)
	`)
