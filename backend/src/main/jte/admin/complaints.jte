@import java.util.function.Function
@import com.workin.backend.platformadmin.hr.Complaint
@import com.workin.backend.platformadmin.web.DashboardListFilters
@import com.workin.backend.platformadmin.web.DashboardPage
@import com.workin.backend.platformadmin.web.DashboardSession
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param DashboardPage<Complaint> result
@param DashboardListFilters filters
@param String source
@param String status
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
!{int colCount = showCompanyColumn ? 8 : 7;}
<%-- dashboard/pages/complaints/page.php. --%>
@template.admin.layout(
	title = t.apply(session.isCompany() ? "nav_complaints" : "nav_complaints_admin"),
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentAdminPhone = currentAdminPhone,
	currentPage = currentPage,
	lang = lang,
	session = session,
	t = t,
	available = available,
	pageStyles = java.util.List.of("hr-pages.css"),
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
						<label class="filter-field__label" for="cp_company">${t.apply("company")}</label>
						<input type="number" id="cp_company" name="company_id" min="0"
						       placeholder="${t.apply("filter_all")}"
						       value="${filters.companyId() > 0 ? String.valueOf(filters.companyId()) : ""}">
					</div>
					<%-- A company owner has no source filter: it sees only its
					     employees' complaints, never the ones it raised itself. --%>
					<div class="filter-field">
						<label class="filter-field__label" for="cp_source">${t.apply("complaint_source")}</label>
						<select id="cp_source" name="source">
							<option value="">${t.apply("filter_all")}</option>
							<option value="employee" selected="${"employee".equals(source)}">
								${t.apply("complaint_source_employee_to_company")}</option>
							<option value="company_support" selected="${"company_support".equals(source)}">
								${t.apply("complaint_source_company_to_admin")}</option>
						</select>
					</div>
				@endif
				<div class="filter-field">
					<label class="filter-field__label" for="cp_search">${t.apply("search")}</label>
					<input type="search" id="cp_search" name="search"
					       placeholder="${t.apply("complaints_search_placeholder")}"
					       value="${filters.search()}">
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="cp_status">${t.apply("status")}</label>
					<select id="cp_status" name="status">
						<option value="">${t.apply("filter_all")}</option>
						<option value="pending" selected="${"pending".equals(status)}">${t.apply("status_pending")}</option>
						<option value="done" selected="${"done".equals(status)}">${t.apply("status_done")}</option>
						<option value="closed" selected="${"closed".equals(status)}">${t.apply("status_closed")}</option>
					</select>
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="cp_from">${t.apply("from_date")}</label>
					<input type="date" id="cp_from" name="date_from" value="${dateFrom}">
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="cp_to">${t.apply("to_date")}</label>
					<input type="date" id="cp_to" name="date_to" value="${dateTo}">
				</div>
				<button type="submit" class="btn btn-blue">${t.apply("search")}</button>
			</form>
		</div>

		<div class="data-table-card">
			<div class="data-table-head">
				<h2 class="data-table-title">
					${t.apply(session.isCompany() ? "nav_complaints" : "nav_complaints_admin")}
					(${result.total()})</h2>
			</div>
			<div class="data-table-wrap">
			<table class="data-table">
				<thead>
					<tr>
						<th>#</th>
						@if(showCompanyColumn)<th>${t.apply("company")}</th>@endif
						<th>${t.apply("complaint_submitter")}</th>
						<th>${t.apply("phone")}</th>
						<th>${t.apply("complaint_msg")}</th>
						<th>${t.apply("complaint_source")}</th>
						<th>${t.apply("status")}</th>
						<th>${t.apply("actions")}</th>
					</tr>
				</thead>
				<tbody>
					@for(int index = 0; index < result.data().size(); index++)
						!{Complaint row = result.data().get(index);}
						<tr>
							<td>${result.from() + index}</td>
							@if(showCompanyColumn)
								<td class="text-muted">${row.companyName() == null ? "—" : row.companyName()}</td>
							@endif
							<td class="bold">${row.name() == null ? "—" : row.name()}</td>
							<td class="text-muted">${row.phone() == null ? "—" : row.phone()}</td>
							<td>${row.message() == null ? "—" : row.message()}</td>
							<td>
								<span class="badge badge-gray" title="${t.apply(row.fromEmployee()
									? "complaint_source_employee_to_company_hint"
									: "complaint_source_company_to_admin_hint")}">
									${t.apply(row.fromEmployee()
										? "complaint_source_employee_to_company"
										: "complaint_source_company_to_admin")}</span>
							</td>
							<td>
								<span class="badge ${"done".equals(row.status()) ? "badge-green"
									: "closed".equals(row.status()) ? "badge-gray" : "badge-blue"}">
									${t.apply("status_" + row.status())}</span>
							</td>
							<td class="col-actions">
								@if(canWrite)
									<%-- The reply carries a status of its own. Legacy writes
									     it unvalidated and a value outside the enum lands as
									     an empty string under production's sql_mode, so the
									     choices here are the only three (R-048). --%>
									<form method="POST" class="inline-form">
										<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
										<input type="hidden" name="action" value="reply">
										<input type="hidden" name="id" value="${row.id()}">
										<input type="text" name="reply" class="inline-input"
										       value="${row.reply() == null ? "" : row.reply()}"
										       placeholder="${t.apply("admin_reply")}"
										       aria-label="${t.apply("admin_reply")}">
										<select name="status" aria-label="${t.apply("status")}">
											<option value="pending" selected="${"pending".equals(row.status())}">${t.apply("status_pending")}</option>
											<option value="done" selected="${"done".equals(row.status())}">${t.apply("status_done")}</option>
											<option value="closed" selected="${"closed".equals(row.status())}">${t.apply("status_closed")}</option>
										</select>
										<button type="submit" class="btn btn-outline btn-sm">${t.apply("save")}</button>
									</form>
									<form method="POST" class="inline-form"
									      onsubmit="return confirm('${t.apply("confirm_delete")}')">
										<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
										<input type="hidden" name="action" value="delete">
										<input type="hidden" name="id" value="${row.id()}">
										<button type="submit" class="btn btn-danger btn-sm">${t.apply("delete")}</button>
									</form>
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
			@if(result.pages() > 1)
				<div class="pager">
					@for(int page = 1; page <= result.pages(); page++)
						<a href="/admin/complaints?page=${page}${filters.asQueryTail()}"
						   class="pager__link${page == result.page() ? " is-current" : ""}">${page}</a>
					@endfor
				</div>
			@endif
		</div>
	`)
