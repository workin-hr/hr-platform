@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.hr.Penalty
@import com.workin.backend.platformadmin.web.DashboardListFilters
@import com.workin.backend.platformadmin.web.DashboardPage
@import com.workin.backend.platformadmin.web.DashboardSession
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param DashboardPage<Penalty> result
@param DashboardListFilters filters
@param List<com.workin.backend.platformadmin.hr.LeaveBalance.EmployeeOption> employeeOptions
@param String applied
@param String dateFrom
@param String dateTo
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
!{int colCount = showCompanyColumn ? 10 : 9;}
<%-- dashboard/pages/penalties/page.php. --%>
@template.admin.layout(
	title = t.apply("nav_penalties"),
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
						<label class="filter-field__label" for="pn_company">${t.apply("company")}</label>
						<input type="number" id="pn_company" name="company_id" min="0"
						       placeholder="${t.apply("filter_all")}"
						       value="${filters.companyId() > 0 ? String.valueOf(filters.companyId()) : ""}">
					</div>
				@endif
				<div class="filter-field">
					<label class="filter-field__label" for="pn_search">${t.apply("search")}</label>
					<input type="search" id="pn_search" name="search"
					       placeholder="${t.apply("leave_balances_search_placeholder")}"
					       value="${filters.search()}">
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="pn_applied">${t.apply("applied_payroll")}</label>
					<select id="pn_applied" name="applied">
						<option value="all" selected="${"all".equals(applied)}">${t.apply("filter_all")}</option>
						<option value="0" selected="${"0".equals(applied)}">${t.apply("no")}</option>
						<option value="1" selected="${"1".equals(applied)}">${t.apply("yes")}</option>
					</select>
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="pn_from">${t.apply("from_date")}</label>
					<input type="date" id="pn_from" name="date_from" value="${dateFrom}">
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="pn_to">${t.apply("to_date")}</label>
					<input type="date" id="pn_to" name="date_to" value="${dateTo}">
				</div>
				<button type="submit" class="btn btn-blue">${t.apply("search")}</button>
			</form>
		</div>


		<div class="data-table-card">
			<div class="data-table-head">
				<h2 class="data-table-title">${t.apply("nav_penalties")} (${result.total()})</h2>
				<div class="data-table-head__actions">
					@if(canWrite)
						<button type="button" class="btn btn-blue btn-sm"
								onclick="crudOpenAdd('penModal')">+ ${t.apply("add_penalty")}</button>
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
						<th>${t.apply("penalty_type")}</th>
						<th>${t.apply("penalty_days")}</th>
						<th>${t.apply("penalty_reason")}</th>
						<th>${t.apply("penalty_date")}</th>
						<th>${t.apply("applied_payroll")}</th>
						<th>${t.apply("actions")}</th>
					</tr>
				</thead>
				<tbody>
					@for(int index = 0; index < result.data().size(); index++)
						!{Penalty row = result.data().get(index);}
						<tr>
							<td>${result.from() + index}</td>
							<td class="text-muted">${row.employeeCode() == null ? "—" : row.employeeCode()}</td>
							<td class="bold">${row.employeeName()}</td>
							@if(showCompanyColumn)
								<td class="text-muted">${row.companyName() == null ? "—" : row.companyName()}</td>
							@endif
							<td>${row.penaltyType()}</td>
							<td><span class="badge badge-gray">${row.daysDisplay()}</span></td>
							<td class="text-muted tbl-clip" title="${row.reason() == null ? "" : row.reason()}">${row.reason() == null || row.reason().isBlank() ? "—" : row.reason()}</td>
							<td>${row.penaltyDate() == null ? "—" : row.penaltyDate()}</td>
							<td>
								<span class="badge ${row.appliedToPayroll() ? "badge-green" : "badge-gray"}">
									${t.apply(row.appliedToPayroll() ? "yes" : "no")}</span>
							</td>
							<td class="col-actions">
								<%-- The dash belongs beside the row, not inside a popover the
								     reader has to open to be told there is nothing in it. --%>
								@if(canWrite)
									<%-- Once payroll has deducted against a penalty its days are
									     frozen: a payslip has already been computed from them.
									     Legacy refuses the edit; so does this. --%>
									@template.admin.rowActions(rowId = row.id(), t = t, items = @`
										@if(!row.appliedToPayroll())
											<form method="POST" class="row-actions__form" role="none">
												<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
												<input type="hidden" name="action" value="mark_applied">
												<input type="hidden" name="id" value="${row.id()}">
												<button type="submit" role="menuitem" class="row-actions__item">${t.apply("applied_payroll")}</button>
											</form>
										@endif
										<form method="POST" class="row-actions__form" role="none"
										      onsubmit="return confirm('${t.apply("confirm_delete")}')">
											<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
											<input type="hidden" name="action" value="delete_penalty">
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
						<tr><td colspan="${colCount}" class="data-table-empty">${t.apply("no_data")}</td></tr>
					@endif
				</tbody>
			</table>
			</div>
			!{java.util.SequencedMap<String, String> carry =
					new java.util.LinkedHashMap<>(filters.asQueryParameters());}
			!{carry.put("applied", applied);}
			@template.admin.pager(basePath = "/admin/penalties", result = result,
					carry = carry, t = t)
		@if(canWrite)
			<%-- Legacy opens this form in a modal, behind the "+" button above.
			     Rendered inline it sat open over the table on every visit, whether
			     or not anyone was adding anything. Same form, same fields, same
			     POST; .modal-bg is position:fixed, so it lives at the end of the
			     document and out of the reading order. --%>
			<div class="modal-bg" id="penModal">
				<div class="modal" role="dialog" aria-modal="true" aria-labelledby="penModal-title">
					<button type="button" class="modal-close"
							aria-label="${t.apply("cancel")}">&times;</button>
					<h2 id="penModal-title">${t.apply("add_penalty")}</h2>
					<form method="POST">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="add_penalty">
					<div class="org-form-grid">
					<div class="form-row">
						<label for="employee_id">${t.apply("employee_name")}</label>
						<select id="employee_id" name="employee_id" required>
							<option value="0">—</option>
							@for(com.workin.backend.platformadmin.hr.LeaveBalance.EmployeeOption option : employeeOptions)
								<option value="${option.id()}">${option.companyName() == null
									? option.code() + " — " + option.name()
									: option.companyName() + " · " + option.code() + " — " + option.name()}</option>
							@endfor
						</select>
					</div>
					<div class="form-row">
						<label for="penalty_type">${t.apply("penalty_type")}</label>
						<input type="text" id="penalty_type" name="penalty_type" required maxlength="255">
					</div>
					<%-- Seven allowed values, shared with payroll's own whitelist:
					     a quarter day through five days. Anything else is refused
					     rather than rounded. --%>
					<div class="form-row">
						<label for="penalty_days">${t.apply("penalty_days")}</label>
						<select id="penalty_days" name="penalty_days" required>
							<option value="0.25">0.25</option>
							<option value="0.5">0.5</option>
							<option value="1" selected="true">1</option>
							<option value="2">2</option>
							<option value="3">3</option>
							<option value="4">4</option>
							<option value="5">5</option>
						</select>
					</div>
					<div class="form-row">
						<label for="penalty_date">${t.apply("penalty_date")}</label>
						<input type="date" id="penalty_date" name="penalty_date">
					</div>
					<div class="form-row">
						<label for="reason">${t.apply("penalty_reason")}</label>
						<input type="text" id="reason" name="reason" maxlength="500">
					</div>
					</div>
					<div class="form-footer">
						<button type="submit" class="btn btn-yellow">${t.apply("save")}</button>
					</div>
				</form>
				</div>
			</div>
		@endif
		</div>
	`)
