@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.hr.LeaveBalance
@import com.workin.backend.platformadmin.web.DashboardListFilters
@import com.workin.backend.platformadmin.web.DashboardPage
@import com.workin.backend.platformadmin.web.DashboardSession
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param DashboardPage<LeaveBalance> result
@param DashboardListFilters filters
@param List<LeaveBalance.EmployeeOption> employeeOptions
@param List<Integer> yearOptions
@param int year
@param boolean showCompanyColumn
@param boolean canManage
@param boolean actionsEnabled
@param String errorKey = null
@param String currentPage
@param String lang
@param DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
!{boolean canWrite = canManage && actionsEnabled;}
!{int colCount = showCompanyColumn ? 9 : 8;}
<%-- dashboard/pages/leave_balances/page.php. --%>
@template.admin.layout(
	title = t.apply("nav_leave_balances"),
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentAdminPhone = currentAdminPhone,
	currentPage = currentPage,
	lang = lang,
	session = session,
	t = t,
	available = available,
	pageStyles = java.util.List.of("hr-pages.css", "leave-balance-form.css"),
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
						<label class="filter-field__label" for="lb_company">${t.apply("company")}</label>
						<input type="number" id="lb_company" name="company_id" min="0"
						       placeholder="${t.apply("filter_all")}"
						       value="${filters.companyId() > 0 ? String.valueOf(filters.companyId()) : ""}">
					</div>
				@endif
				<div class="filter-field">
					<label class="filter-field__label" for="lb_search">${t.apply("search")}</label>
					<input type="search" id="lb_search" name="search"
					       placeholder="${t.apply("leave_balances_search_placeholder")}"
					       value="${filters.search()}">
				</div>
				<%-- Always one year, never "all": a balance only means anything
				     inside the year it was granted for. --%>
				<div class="filter-field">
					<label class="filter-field__label" for="lb_year">${t.apply("year")}</label>
					<select id="lb_year" name="year">
						@for(Integer option : yearOptions)
							<option value="${option}" selected="${option == year}">${option}</option>
						@endfor
					</select>
				</div>
				<button type="submit" class="btn btn-blue">${t.apply("search")}</button>
			</form>
		</div>


		<div class="data-table-card">
			<div class="data-table-head">
				<h2 class="data-table-title">${t.apply("nav_leave_balances")} (${result.total()})</h2>
				<div class="data-table-head__actions">
					@if(canWrite)
						<button type="button" class="btn btn-blue btn-sm"
								onclick="crudOpenAdd('leaveModal')">+ ${t.apply("add_leave_balance")}</button>
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
						@if(showCompanyColumn)<th>${t.apply("company")}</th>@endif
						<th>${t.apply("year")}</th>
						<th>${t.apply("total_days")}</th>
						<th>${t.apply("used_days")}</th>
						<th>${t.apply("remaining_days")}</th>
						<th>${t.apply("actions")}</th>
					</tr>
				</thead>
				<tbody>
					@for(int index = 0; index < result.data().size(); index++)
						!{LeaveBalance row = result.data().get(index);}
						<tr>
							<td>${result.from() + index}</td>
							<td class="text-muted">${row.employeeCode() == null ? "—" : row.employeeCode()}</td>
							<td class="bold">${row.employeeName()}</td>
							@if(showCompanyColumn)
								<td class="text-muted">${row.companyName() == null ? "—" : row.companyName()}</td>
							@endif
							<td>${row.year()}</td>
							<td><span class="badge badge-gray">${row.totalDisplay()}</span></td>
							<td><span class="badge badge-gray">${row.usedDisplay()}</span></td>
							<td><span class="badge badge-blue">${row.remainingDisplay()}</span></td>
							<%-- A hard delete, unlike the org pages' deactivate: nothing
							     references a balance row, so there is nothing to orphan --
							     and nothing to recover either. --%>
							<td class="col-actions">
								@if(canWrite)
									@template.admin.rowActions(rowId = row.id(), t = t, items = @`
										<button type="button" role="menuitem" class="row-actions__item"
												data-dialog="leave-edit"
												data-dialog-id="${row.id()}"
												data-dialog-subject="${row.employeeName()}"
												data-dialog-total="${row.totalDisplay()}"
												data-dialog-used="${row.usedDisplay()}">${t.apply("edit")}</button>
										<form method="POST" class="row-actions__form" role="none"
										      onsubmit="return confirm('${t.apply("confirm_delete")}')">
											<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
											<input type="hidden" name="action" value="delete_leave">
											<input type="hidden" name="id" value="${row.id()}">
											<button type="submit" role="menuitem"
													class="row-actions__item row-actions__item--danger">${t.apply("delete")}</button>
										</form>
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
			!{java.util.SequencedMap<String, String> carry =
					new java.util.LinkedHashMap<>(filters.asQueryParameters());}
			!{carry.put("year", String.valueOf(year));}
			@template.admin.pager(basePath = "/admin/leave_balances", result = result,
					carry = carry, t = t)
		@if(canWrite)
			<%-- Two number boxes per row is what kept this page's actions out of
			     the menu. Same two fields, same action, one dialog. --%>
			@template.admin.rowDialog(id = "leave-edit", action = "edit_leave",
					title = t.apply("edit"), csrfParameterName = csrfParameterName,
					csrfToken = csrfToken, t = t, submitLabel = t.apply("save"),
					fields = @`
						<label>${t.apply("total_days")}
							<input type="number" name="total_days" step="0.5" min="0"
							       data-dialog-field="total"></label>
						<label>${t.apply("used_days")}
							<input type="number" name="used_days" step="0.5" min="0"
							       data-dialog-field="used"></label>
					`)
		@endif
		@if(canWrite)
			<%-- Legacy opens this form in a modal, behind the "+" button above.
			     Rendered inline it sat open over the table on every visit, whether
			     or not anyone was adding anything. Same form, same fields, same
			     POST; .modal-bg is position:fixed, so it lives at the end of the
			     document and out of the reading order. --%>
			<div class="modal-bg" id="leaveModal">
				<div class="modal" role="dialog" aria-modal="true" aria-labelledby="leaveModal-title">
					<button type="button" class="modal-close"
							aria-label="${t.apply("cancel")}">&times;</button>
					<h2 id="leaveModal-title">${t.apply("add_leave_balance")}</h2>
					<form method="POST" class="account-form org-form">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="add_leave">
					<div class="org-form-grid">
					<div class="form-row">
						<label for="employee_id">${t.apply("employee_name")}</label>
						<select id="employee_id" name="employee_id" required>
							<option value="0">—</option>
							@for(LeaveBalance.EmployeeOption option : employeeOptions)
								<option value="${option.id()}">${option.companyName() == null
									? option.code() + " — " + option.name()
									: option.companyName() + " · " + option.code() + " — " + option.name()}</option>
							@endfor
						</select>
					</div>
					<div class="form-row">
						<label for="add_year">${t.apply("year")}</label>
						<input type="number" id="add_year" name="year" value="${year}" required>
					</div>
					<div class="form-row">
						<label for="add_total">${t.apply("total_days")}</label>
						<input type="number" id="add_total" name="total_days" step="0.5" min="0"
						       value="15" required>
					</div>
					</div>
					<div class="account-form__footer">
						<button type="submit" class="btn btn-yellow">${t.apply("save")}</button>
					</div>
				</form>
				</div>
			</div>
		@endif
		</div>
	`)
