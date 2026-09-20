@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.hr.EmployeeDisplay
@import com.workin.backend.platformadmin.hr.EmployeeRequest
@import com.workin.backend.platformadmin.hr.ListDisplay
@import com.workin.backend.platformadmin.web.DashboardListFilters
@import com.workin.backend.platformadmin.web.DashboardPage
@import com.workin.backend.platformadmin.web.DashboardSession
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param DashboardPage<EmployeeRequest> result
@param DashboardListFilters filters
@param java.util.function.Supplier<java.util.List<com.workin.backend.platformadmin.org.ActiveCompanies.CompanyOption>> companyFilterOptions
@param List<EmployeeRequest.TypeOption> typeOptions
@param String typesByCompany
@param String status
@param long typeId
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
@param String flash = null
@param String flashType = "success"
@param Function<String, Boolean> available
!{boolean canWrite = canManage && actionsEnabled;}
!{int colCount = showCompanyColumn ? 14 : 13;}
<%-- dashboard/pages/requests/page.php. --%>
@template.admin.layout(
	title = t.apply("nav_employee_requests"),
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
	pageStyles = java.util.List.of("hr-pages.css"),
	pageScripts = java.util.List.of("request-filter-cascade.js"),
	body = @`
		@if(errorKey != null)
			<div class="flash flash-error">${t.apply(errorKey)}</div>
		@endif
		@if(canManage && !actionsEnabled)
			<div class="flash flash-warning">${t.apply("admin_actions_disabled")}</div>
		@endif

		<div class="toolbar page-toolbar page-toolbar-filters">
			<%-- hr_request_filter_form_attrs() (hr_list_helper.php:970-979): request-filter-cascade.js
			     lists the chosen company's request types, and asks for a company without one. --%>
			<form method="GET" class="toolbar-form toolbar-form--labeled"
			      data-request-filters="${typesByCompany == null ? null : "1"}"
			      data-request-types-by-company="${typesByCompany}"
			      data-filter-all="${t.apply("filter_all")}"
			      data-select-company-msg="${t.apply("select_company_first_branches")}"
			      data-selected-request-type="${typeId}">
				@if(!session.isScopedToOneCompany())
					@template.admin.companyFilterField(companyId = filters.companyId(), companies = companyFilterOptions, t = t)
				@endif
				<div class="filter-field">
					<label class="filter-field__label" for="rq_search">${t.apply("search")}</label>
					<input type="search" id="rq_search" name="search"
					       placeholder="${t.apply("search")}..." value="${filters.search()}">
				</div>
				<%-- Defaults to pending: the page is a queue to work through. --%>
				<div class="filter-field">
					<label class="filter-field__label" for="rq_status">${t.apply("status")}</label>
					<select id="rq_status" name="status">
						<option value="pending" selected="${"pending".equals(status)}">${t.apply("status_pending")}</option>
						<option value="approved" selected="${"approved".equals(status)}">${t.apply("status_approved")}</option>
						<option value="rejected" selected="${"rejected".equals(status)}">${t.apply("status_rejected")}</option>
						<option value="all" selected="${"all".equals(status)}">${t.apply("filter_all")}</option>
					</select>
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="rq_type">${t.apply("request_type")}</label>
					<%-- hr_render_request_type_filter_field() (hr_list_helper.php:981-999): a type is one
					     company's, so with no company chosen the select asks for one. --%>
					!{boolean needsCompany = !session.isScopedToOneCompany() && filters.companyId() <= 0;}
					<select id="rq_type" name="type_id" data-filter-request-type disabled="${needsCompany}">
						<option value="0">${t.apply("filter_all")}</option>
						@if(needsCompany)
							<option value="0" disabled>${t.apply("select_company_first_branches")}</option>
						@else
							@for(EmployeeRequest.TypeOption option : typeOptions)
								<option value="${option.id()}" selected="${typeId == option.id()}">${option.name()}</option>
							@endfor
						@endif
					</select>
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="rq_from">${t.apply("from_date")}</label>
					<input type="date" id="rq_from" name="date_from" value="${dateFrom}">
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="rq_to">${t.apply("to_date")}</label>
					<input type="date" id="rq_to" name="date_to" value="${dateTo}">
				</div>
				<button type="submit" class="btn btn-blue">${t.apply("search")}</button>
			</form>
		</div>

		<div class="data-table-card">
			<div class="data-table-head">
				<h2 class="data-table-title">${t.apply("nav_employee_requests")} (${result.total()})</h2>
			</div>
			<div class="table-wrap">
			<table class="tbl">
				<thead>
					<tr>
						<th>#</th>
						<th>${t.apply("emp_code")}</th>
						<th>${t.apply("employee_name")}</th>
						@if(showCompanyColumn)<th>${t.apply("company")}</th>@endif
						<th>${t.apply("request_type")}</th>
						<th>${t.apply("from_date")}</th>
						<th>${t.apply("to_date")}</th>
						<%-- A part-day request carries a time as well as a date, and
						     legacy gives each its own column. --%>
						<th>${t.apply("from_time")}</th>
						<th>${t.apply("to_time")}</th>
						<th>${t.apply("request_notes")}</th>
						<th>${t.apply("admin_reply")}</th>
						<th>${t.apply("status")}</th>
						<th>${t.apply("reg_date")}</th>
						@template.admin.actionsHeader(t = t)
					</tr>
				</thead>
				<tbody>
					@for(int index = 0; index < result.data().size(); index++)
						!{EmployeeRequest row = result.data().get(index);}
						<%-- dashboard_employee_display_name(): a blank name reads as an em dash, in
						     the cell and in the row's actions (requests/page.php:122, hr_list_helper.php:524). --%>
						!{String employeeName = EmployeeDisplay.displayName(row.employeeName(), "—");}
						<tr>
							<td>${result.from() + index}</td>
							<td class="text-muted">${row.employeeCode() == null ? "—" : row.employeeCode()}</td>
							<td class="bold">${employeeName}</td>
							@if(showCompanyColumn)
								<td class="text-muted">${row.companyName() == null ? "—" : row.companyName()}</td>
							@endif
							<td>
								${row.requestTypeName()}
								@if(row.deductBalance())
									<span class="badge badge-gray">${t.apply("deduct_balance")}</span>
								@endif
							</td>
							<td>${row.fromDate() == null ? "—" : row.fromDate()}</td>
							<td>${row.toDate() == null ? "—" : row.toDate()}</td>
							<td class="text-muted" dir="ltr">${row.fromTime() == null || row.fromTime().isBlank()
								? "—" : row.fromTime()}</td>
							<td class="text-muted" dir="ltr">${row.toTime() == null || row.toTime().isBlank()
								? "—" : row.toTime()}</td>
							<td class="text-muted" title="${row.notes() == null ? "" : row.notes()}">${ListDisplay.notes(row.notes(), 60)}</td>
							<td class="text-muted" title="${row.reply() == null ? "" : row.reply()}">${ListDisplay.notes(row.reply(), 60)}</td>
							<td>
								@template.admin.statusBadge(status = row.status(), t = t)
							</td>
							<td>${row.createdDate()}</td>
							<%-- Approving is not a status change alone: it deducts leave
							     and can write one attendance row per day of the span, which
							     is why it asks before it runs. --%>
							<td class="col-actions">
								@if(canWrite)
									@template.admin.rowActions(rowId = row.id(), t = t, items = @`
										@if(row.isPending())
											<button type="button" role="menuitem"
													class="row-actions__item row-actions__item--success"
													data-dialog="request-approve"
													data-dialog-id="${row.id()}"
													data-dialog-subject="${employeeName}">${t.apply("approve")}</button>
											<button type="button" role="menuitem" class="row-actions__item row-actions__item--danger"
													data-dialog="request-reject"
													data-dialog-id="${row.id()}"
													data-dialog-subject="${employeeName}">${t.apply("reject")}</button>
										@endif
										<form method="POST" class="row-actions__form" role="none"
										      onsubmit="return confirm('${t.apply("confirm_delete")}')">
											<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
											<input type="hidden" name="action" value="delete">
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
		@if(canWrite)
			<%-- The comment box used to sit in every row. It is optional -- legacy
			     accepts an empty one -- so the dialog exists to say what approving
			     does, not to demand text. --%>
			@template.admin.rowDialog(id = "request-approve", action = "approve",
					title = t.apply("approve"), csrfParameterName = csrfParameterName,
					csrfToken = csrfToken, t = t, submitLabel = t.apply("approve"),
					submitVariant = "green",
					fields = @`
						<div class="form-row">
							<label for="req_approve_comment">${t.apply("reply")}</label>
							<textarea id="req_approve_comment" name="comment" rows="4"></textarea>
						</div>
					`)
			<%-- Legacy's reject opens the same decision modal, with an optional
			     reply (requests/page.php:143-157); it never posts on the first click. --%>
			@template.admin.rowDialog(id = "request-reject", action = "reject",
					title = t.apply("reject"), csrfParameterName = csrfParameterName,
					csrfToken = csrfToken, t = t, submitLabel = t.apply("reject"),
					submitVariant = "red",
					fields = @`
						<div class="form-row">
							<label for="req_reject_comment">${t.apply("reply")}</label>
							<textarea id="req_reject_comment" name="comment" rows="4"></textarea>
						</div>
					`)
		@endif
		</div>
		@template.admin.pager(basePath = "/admin/requests", result = result,
				carry = carry, t = t)
	`)
