@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.hr.Advance
@import com.workin.backend.platformadmin.hr.LeaveBalance.EmployeeOption
@import com.workin.backend.platformadmin.web.DashboardListFilters
@import com.workin.backend.platformadmin.web.DashboardPage
@import com.workin.backend.platformadmin.web.DashboardSession
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param DashboardPage<Advance> result
@param DashboardListFilters filters
@param List<EmployeeOption> employeeOptions
@param String status
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
!{int colCount = showCompanyColumn ? 11 : 10;}
<%-- dashboard/pages/advances/page.php. --%>
@template.admin.layout(
	title = t.apply("nav_advances"),
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
						<label class="filter-field__label" for="ad_company">${t.apply("company")}</label>
						<input type="number" id="ad_company" name="company_id" min="0"
						       placeholder="${t.apply("filter_all")}"
						       value="${filters.companyId() > 0 ? String.valueOf(filters.companyId()) : ""}">
					</div>
				@endif
				<div class="filter-field">
					<label class="filter-field__label" for="ad_search">${t.apply("search")}</label>
					<input type="search" id="ad_search" name="search"
					       placeholder="${t.apply("advances_search_placeholder")}"
					       value="${filters.search()}">
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="ad_status">${t.apply("status")}</label>
					<select id="ad_status" name="status">
						<option value="all" selected="${"all".equals(status)}">${t.apply("filter_all")}</option>
						<option value="pending" selected="${"pending".equals(status)}">${t.apply("status_pending")}</option>
						<option value="approved" selected="${"approved".equals(status)}">${t.apply("status_approved")}</option>
						<option value="rejected" selected="${"rejected".equals(status)}">${t.apply("status_rejected")}</option>
					</select>
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="ad_from">${t.apply("from_date")}</label>
					<input type="date" id="ad_from" name="date_from" value="${dateFrom}">
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="ad_to">${t.apply("to_date")}</label>
					<input type="date" id="ad_to" name="date_to" value="${dateTo}">
				</div>
				<button type="submit" class="btn btn-blue">${t.apply("search")}</button>
			</form>
		</div>


		<div class="data-table-card">
			<div class="data-table-head">
				<h2 class="data-table-title">${t.apply("nav_advances")} (${result.total()})</h2>
				<div class="data-table-head__actions">
					@if(canWrite)
						<button type="button" class="btn btn-blue btn-sm"
								onclick="crudOpenAdd('advModal')">+ ${t.apply("add_advance")}</button>
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
						<th>${t.apply("advance_amount")}</th>
						<th>${t.apply("remaining")}</th>
						<th>${t.apply("request_date")}</th>
						<th>${t.apply("advance_reason")}</th>
						<th>${t.apply("rejection_reason")}</th>
						<th>${t.apply("status")}</th>
						<th>${t.apply("actions")}</th>
					</tr>
				</thead>
				<tbody>
					@for(int index = 0; index < result.data().size(); index++)
						!{Advance row = result.data().get(index);}
						<tr>
							<td>${result.from() + index}</td>
							<td class="text-muted">${row.employeeCode() == null ? "—" : row.employeeCode()}</td>
							<td class="bold">${row.employeeName()}</td>
							@if(showCompanyColumn)
								<td class="text-muted">${row.companyName() == null ? "—" : row.companyName()}</td>
							@endif
							<td class="bold">${row.amountDisplay()}</td>
							<td><span class="badge badge-blue">${row.remainingDisplay()}</span></td>
							<td>${row.requestDate() == null ? "—" : row.requestDate()}</td>
							<%-- Both reasons are free text and can be long; the cell
							     shows the start and the title carries the whole of it,
							     as legacy does. --%>
							<td class="text-muted tbl-clip" title="${row.reason() == null ? "" : row.reason()}">${row.reason() == null || row.reason().isBlank() ? "—" : row.reason()}</td>
							<td class="text-muted tbl-clip" title="${row.rejectionReason() == null ? "" : row.rejectionReason()}">${row.rejectionReason() == null || row.rejectionReason().isBlank() ? "—" : row.rejectionReason()}</td>
							<td>
								<span class="badge ${row.isApproved() ? "badge-green"
									: row.isRejected() ? "badge-red" : "badge-gray"}">
									${t.apply("status_" + row.status())}</span>
							</td>
							<td class="col-actions">
								@if(canWrite)
									@template.admin.rowActions(rowId = row.id(), t = t, items = @`
										@if(row.isPending())
											<%-- D-210: saveEdit existed with no way in, so a pending
											     advance could be approved, rejected or deleted but
											     never corrected. Pending only, as the others are. --%>
											<button type="button" role="menuitem" class="row-actions__item"
													data-dialog="advance-edit"
													data-dialog-id="${row.id()}"
													data-dialog-subject="${row.employeeName()}"
													data-dialog-employee_id="${row.employeeId()}"
													data-dialog-amount="${row.amount() == null ? "" : row.amount().toPlainString()}"
													data-dialog-reason="${row.reason() == null ? "" : row.reason()}"
													data-dialog-request_date="${row.requestDate() == null ? "" : row.requestDate()}">${t.apply("edit")}</button>
											<form method="POST" class="row-actions__form" role="none"
											      onsubmit="return confirm('${t.apply("confirm_approve_advance")}')">
												<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
												<input type="hidden" name="action" value="approve">
												<input type="hidden" name="id" value="${row.id()}">
												<button type="submit" role="menuitem"
														class="row-actions__item row-actions__item--success">${t.apply("approve")}</button>
											</form>
											<button type="button" role="menuitem" class="row-actions__item"
													data-dialog="advance-reject"
													data-dialog-id="${row.id()}"
													data-dialog-subject="${row.employeeName()}">${t.apply("reject")}</button>
										@endif
										<form method="POST" class="row-actions__form" role="none">
											<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
											<input type="hidden" name="action" value="mark_paid">
											<input type="hidden" name="id" value="${row.id()}">
											<button type="submit" role="menuitem"
													class="row-actions__item">${t.apply("remaining")}: 0</button>
										</form>
										<form method="POST" class="row-actions__form" role="none"
										      onsubmit="return confirm('${t.apply("confirm_delete")}')">
											<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
											<input type="hidden" name="action" value="delete_advance">
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
			!{carry.put("status", status);}
			@template.admin.pager(basePath = "/admin/advances", result = result,
					carry = carry, t = t)
		@if(canWrite)
			<%-- A rejection must say why: the reason is not optional, and it used
			     to be a required text box inside every row of the table. --%>
			@template.admin.rowDialog(id = "advance-reject", action = "reject",
					title = t.apply("reject"), csrfParameterName = csrfParameterName,
					csrfToken = csrfToken, t = t, submitLabel = t.apply("reject"),
					submitVariant = "danger",
					fields = @`
						<label>${t.apply("rejection_reason")}
							<textarea name="rejection_reason" rows="3" required></textarea></label>
					`)
		@endif
		@if(canWrite)
			@template.admin.rowDialog(id = "advance-edit", action = "edit_advance",
					title = t.apply("edit"), csrfParameterName = csrfParameterName,
					csrfToken = csrfToken, t = t, submitLabel = t.apply("save"),
					fields = @`
						<div class="form-row">
							<label>${t.apply("employee_name")}
								<select name="employee_id" required data-dialog-field="employee_id">
									@for(EmployeeOption option : employeeOptions)
										<option value="${option.id()}">${option.companyName() == null
											? option.code() + " — " + option.name()
											: option.companyName() + " · " + option.code() + " — " + option.name()}</option>
									@endfor
								</select></label>
						</div>
						<div class="form-row">
							<label>${t.apply("advance_amount")}
								<input type="number" step="0.01" min="0" name="amount" required
								       data-dialog-field="amount"></label>
							<label>${t.apply("request_date")}
								<input type="date" name="request_date" data-dialog-field="request_date"></label>
						</div>
						<div class="form-row">
							<label>${t.apply("advance_reason")}
								<input type="text" name="reason" maxlength="500"
								       data-dialog-field="reason"></label>
						</div>
					`)

			<%-- Legacy opens this form in a modal, behind the "+" button above.
			     Rendered inline it sat open over the table on every visit, whether
			     or not anyone was adding anything. Same form, same fields, same
			     POST; .modal-bg is position:fixed, so it lives at the end of the
			     document and out of the reading order. --%>
			<div class="modal-bg" id="advModal">
				<div class="modal" role="dialog" aria-modal="true" aria-labelledby="advModal-title">
					<button type="button" class="modal-close"
							aria-label="${t.apply("cancel")}">&times;</button>
					<h2 id="advModal-title">${t.apply("add_advance")}</h2>
					<form method="POST">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="add_advance">
					<div class="org-form-grid">
					<div class="form-row">
						<label for="employee_id">${t.apply("employee_name")}</label>
						<select id="employee_id" name="employee_id" required>
							<option value="0">—</option>
							@for(EmployeeOption option : employeeOptions)
								<option value="${option.id()}">${option.companyName() == null
									? option.code() + " — " + option.name()
									: option.companyName() + " · " + option.code() + " — " + option.name()}</option>
							@endfor
						</select>
					</div>
					<div class="form-row">
						<label for="amount">${t.apply("advance_amount")}</label>
						<input type="number" id="amount" name="amount" step="0.01" min="0.01" required>
					</div>
					<div class="form-row">
						<label for="request_date">${t.apply("request_date")}</label>
						<input type="date" id="request_date" name="request_date">
					</div>
					<div class="form-row">
						<label for="reason">${t.apply("advance_reason")}</label>
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
