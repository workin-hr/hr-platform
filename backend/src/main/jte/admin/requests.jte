@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.hr.EmployeeRequest
@import com.workin.backend.platformadmin.web.DashboardListFilters
@import com.workin.backend.platformadmin.web.DashboardPage
@import com.workin.backend.platformadmin.web.DashboardSession
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param DashboardPage<EmployeeRequest> result
@param DashboardListFilters filters
@param List<EmployeeRequest.TypeOption> typeOptions
@param String status
@param long typeId
@param String dateFrom
@param String dateTo
@param boolean showCompanyColumn
@param boolean canManage
@param boolean actionsEnabled
@param boolean factorBound
@param String errorKey = null
@param String currentPage
@param String lang
@param DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
!{boolean canWrite = canManage && actionsEnabled && factorBound;}
!{int colCount = showCompanyColumn ? 10 : 9;}
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
	available = available,
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
						<label class="filter-field__label" for="rq_company">${t.apply("company")}</label>
						<input type="number" id="rq_company" name="company_id" min="0"
						       placeholder="${t.apply("filter_all")}"
						       value="${filters.companyId() > 0 ? String.valueOf(filters.companyId()) : ""}">
					</div>
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
					<select id="rq_type" name="type_id">
						<option value="0">${t.apply("filter_all")}</option>
						@for(EmployeeRequest.TypeOption option : typeOptions)
							<option value="${option.id()}" selected="${typeId == option.id()}">${option.name()}</option>
						@endfor
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
			<div class="data-table-wrap">
			<table class="data-table">
				<thead>
					<tr>
						<th>#</th>
						<th>${t.apply("emp_code")}</th>
						<th>${t.apply("employee_name")}</th>
						@if(showCompanyColumn)<th>${t.apply("company")}</th>@endif
						<th>${t.apply("request_type")}</th>
						<th>${t.apply("from_date")}</th>
						<th>${t.apply("to_date")}</th>
						<th>${t.apply("status")}</th>
						<th>${t.apply("reg_date")}</th>
						<th>${t.apply("actions")}</th>
					</tr>
				</thead>
				<tbody>
					@for(int index = 0; index < result.data().size(); index++)
						!{EmployeeRequest row = result.data().get(index);}
						<tr>
							<td>${result.from() + index}</td>
							<td class="text-muted">${row.employeeCode() == null ? "—" : row.employeeCode()}</td>
							<td class="bold">${row.employeeName()}</td>
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
							<td>
								<span class="badge ${"approved".equals(row.status()) ? "badge-green"
									: "rejected".equals(row.status()) ? "badge-red" : "badge-gray"}">
									${t.apply("status_" + row.status())}</span>
							</td>
							<td>${row.createdDate()}</td>
							<td class="col-actions">
								@if(canWrite && row.isPending())
									<%-- Approving is not a status change alone: it deducts leave
									     and can write one attendance row per day of the span. --%>
									<form method="POST" class="inline-form">
										<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
										<input type="hidden" name="action" value="approve">
										<input type="hidden" name="id" value="${row.id()}">
										<input type="text" name="comment" class="inline-input"
										       placeholder="${t.apply("reply")}" aria-label="${t.apply("reply")}">
										<button type="submit" class="btn btn-outline btn-sm">${t.apply("approve")}</button>
									</form>
									<form method="POST" class="inline-form">
										<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
										<input type="hidden" name="action" value="reject">
										<input type="hidden" name="id" value="${row.id()}">
										<button type="submit" class="btn btn-outline btn-sm">${t.apply("reject")}</button>
									</form>
								@endif
								@if(canWrite)
									<form method="POST" class="inline-form"
									      onsubmit="return confirm('${t.apply("confirm_delete")}')">
										<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
										<input type="hidden" name="action" value="delete">
										<input type="hidden" name="id" value="${row.id()}">
										<button type="submit" class="btn btn-danger btn-sm">${t.apply("delete")}</button>
									</form>
								@endif
								@if(!canWrite)
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
			@if(result.pages() > 1)
				<div class="pager">
					@for(int page = 1; page <= result.pages(); page++)
						<a href="/admin/requests?page=${page}&status=${status}${filters.asQueryTail()}"
						   class="pager__link${page == result.page() ? " is-current" : ""}">${page}</a>
					@endfor
				</div>
			@endif
		</div>
	`)
