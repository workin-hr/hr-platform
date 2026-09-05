@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.hr.WorkforcePlan
@import com.workin.backend.platformadmin.web.DashboardListFilters
@import com.workin.backend.platformadmin.web.DashboardPage
@import com.workin.backend.platformadmin.web.DashboardSession
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param DashboardPage<WorkforcePlan> result
@param DashboardListFilters filters
@param List<WorkforcePlan.Option> branchOptions
@param List<WorkforcePlan.Option> departmentOptions
@param List<WorkforcePlan.Option> jobTitleOptions
@param boolean canManage
@param boolean actionsEnabled
@param boolean factorBound
@param WorkforcePlan editRow = null
@param boolean addOpen = false
@param String errorKey = null
@param String currentPage
@param String lang
@param DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
!{boolean canWrite = canManage && actionsEnabled && factorBound;}
<%-- dashboard/pages/workforce_planning/page.php. Seven columns, and no
     company column even for an unfiltered administrator -- legacy's header
     list does not have one. --%>
@template.admin.layout(
	title = t.apply("nav_workforce_planning"),
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentAdminPhone = currentAdminPhone,
	currentPage = currentPage,
	lang = lang,
	session = session,
	t = t,
	available = available,
	pageStyles = java.util.List.of("hr-pages.css", "org-form.css"),
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
						<label class="filter-field__label" for="wp_company">${t.apply("company")}</label>
						<input type="number" id="wp_company" name="company_id" min="0"
						       placeholder="${t.apply("filter_all")}"
						       value="${filters.companyId() > 0 ? String.valueOf(filters.companyId()) : ""}">
					</div>
				@endif
				<div class="filter-field">
					<%-- The search matches the job title only, not the branch or
					     the department, though both are shown. --%>
					<label class="filter-field__label" for="wp_search">${t.apply("search")}</label>
					<input type="search" id="wp_search" name="search"
					       placeholder="${t.apply("workforce_search_placeholder")}"
					       value="${filters.search()}">
				</div>
				<button type="submit" class="btn btn-blue">${t.apply("search")}</button>
			</form>
		</div>

		@if(addOpen || editRow != null)
			<div class="data-table-card">
				<div class="data-table-head">
					<h2 class="data-table-title">
						${editRow == null ? t.apply("add_workforce")
							: t.apply("edit") + " " + t.apply("workforce_item")}</h2>
				</div>
				<form method="POST" class="account-form org-form">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="${editRow == null ? "add_wp" : "edit_wp"}">
					@if(editRow != null)
						<input type="hidden" name="id" value="${editRow.id()}">
					@endif
					<%-- Posted on the create path only. On an edit the row's own
					     company decides everything and this value is not read:
					     D-176. --%>
					@if(editRow == null)
						<input type="hidden" name="company_id" value="${filters.companyId()}">
					@endif

					<div class="form-row">
						<label for="branch_id">${t.apply("branch")} *</label>
						<select id="branch_id" name="branch_id" required>
							@for(WorkforcePlan.Option option : branchOptions)
								<option value="${option.id()}"
								        selected="${editRow != null && editRow.branchId() == option.id()}">
									${option.label()}</option>
							@endfor
						</select>
					</div>
					<div class="form-row">
						<%-- Optional: 0 is how "no department" is posted, and the
						     column is NOT NULL DEFAULT 0 rather than nullable. --%>
						<label for="department_id">${t.apply("department")}</label>
						<select id="department_id" name="department_id">
							<option value="0">—</option>
							@for(WorkforcePlan.Option option : departmentOptions)
								<option value="${option.id()}"
								        selected="${editRow != null && editRow.departmentId() == option.id()}">
									${option.label()}</option>
							@endfor
						</select>
					</div>
					<div class="form-row">
						<label for="job_title_id">${t.apply("job_title")} *</label>
						<select id="job_title_id" name="job_title_id" required>
							@for(WorkforcePlan.Option option : jobTitleOptions)
								<option value="${option.id()}"
								        selected="${editRow != null && editRow.jobTitleId() == option.id()}">
									${option.label()}</option>
							@endfor
						</select>
					</div>
					<div class="form-row">
						<label for="planned_count">${t.apply("planned_count")} *</label>
						<input type="number" id="planned_count" name="planned_count" min="0" required
						       value="${editRow == null ? "1" : String.valueOf(editRow.plannedCount())}">
					</div>
					<div class="account-form__footer">
						<a href="/admin/workforce_planning" class="btn btn-gray">${t.apply("cancel")}</a>
						@if(canWrite)
							<button type="submit" class="btn btn-yellow">${t.apply("save")}</button>
						@endif
					</div>
				</form>
			</div>
		@endif

		<div class="data-table-card">
			<div class="data-table-head">
				<h2 class="data-table-title">${t.apply("nav_workforce_planning")} (${result.total()})</h2>
				@if(canManage)
					<a href="/admin/workforce_planning?action=add" class="btn btn-blue">+ ${t.apply("add_workforce")}</a>
				@endif
			</div>
			<div class="data-table-wrap">
			<table class="data-table">
				<thead>
					<tr>
						<th>#</th>
						<th>${t.apply("branch")}</th>
						<th>${t.apply("department")}</th>
						<th>${t.apply("job_title")}</th>
						<th>${t.apply("planned_count")}</th>
						<th>${t.apply("actual_count")}</th>
						<th>${t.apply("actions")}</th>
					</tr>
				</thead>
				<tbody>
					@for(int index = 0; index < result.data().size(); index++)
						!{WorkforcePlan row = result.data().get(index);}
						<tr>
							<td>${result.from() + index}</td>
							<td>${row.branchLabel()}</td>
							<td>${row.departmentLabel()}</td>
							<td class="bold">${row.jobTitleLabel()}</td>
							<td>${row.plannedCount()}</td>
							<td class="${row.understaffed() ? "text-red" : "text-green"}">${row.actualCount()}</td>
							<td class="col-actions">
								@if(canManage)
									<a href="/admin/workforce_planning?action=edit&id=${row.id()}"
									   class="btn btn-outline btn-sm">${t.apply("edit")}</a>
									@if(canWrite)
										<form method="POST" class="inline-form"
										      onsubmit="return confirm('${t.apply("confirm_delete")}')">
											<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
											<input type="hidden" name="action" value="delete_wp">
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
						<tr><td colspan="7" class="data-table-empty">${t.apply("no_data")}</td></tr>
					@endif
				</tbody>
			</table>
			</div>
			@if(result.pages() > 1)
				<div class="pager">
					@for(int page = 1; page <= result.pages(); page++)
						<a href="/admin/workforce_planning?page=${page}${filters.asQueryTail()}"
						   class="pager__link${page == result.page() ? " is-current" : ""}">${page}</a>
					@endfor
				</div>
			@endif
		</div>
	`)
