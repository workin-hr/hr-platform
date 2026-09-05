@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.hr.JoinRequest
@import com.workin.backend.platformadmin.web.DashboardListFilters
@import com.workin.backend.platformadmin.web.DashboardSession
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param List<JoinRequest> items
@param DashboardListFilters filters
@param String status
@param List<String> statuses
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
<%-- dashboard/pages/join_requests/page.php. A join request is an employees
     row that has not been admitted yet, so this page's permission and its
     stylesheet are the employees page's. --%>
@template.admin.layout(
	title = t.apply("nav_join_requests"),
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

	<div class="page-toolbar page-toolbar-filters">
		<form method="GET" class="toolbar-form toolbar-form--labeled">
			<div class="filter-field">
				<label class="filter-field__label" for="join-status">${t.apply("status")}</label>
				<%-- "rejected" is offered and is always empty: rejecting deletes
				     the employee row rather than setting the status, here and in
				     the API alike. Legacy offers it, so the port does. --%>
				<select id="join-status" name="status" onchange="this.form.submit()">
					@for(String option : statuses)
						<option value="${option}" selected="${status.equals(option)}">
							${t.apply("pending".equals(option) ? "status_pending"
								: "accepted".equals(option) ? "status_approved"
								: "rejected".equals(option) ? "status_rejected" : "filter_all")}
						</option>
					@endfor
				</select>
			</div>
		</form>
	</div>

	<div class="data-table-card">
		<div class="data-table-head">
			<h2 class="data-table-title">${t.apply("nav_join_requests")} (${items.size()})</h2>
		</div>
		<div class="data-table-wrap">
		<table class="data-table">
			<thead>
				<tr>
					<th>#</th>
					<th>${t.apply("employee_name")}</th>
					<th>${t.apply("phone")}</th>
					<th>${t.apply("join_request_status")}</th>
					<th>${t.apply("request_date")}</th>
					<th>${t.apply("actions")}</th>
				</tr>
			</thead>
			<tbody>
			@if(items.isEmpty())
				<tr><td colspan="6" class="text-muted">${t.apply("join_requests_empty_sub")}</td></tr>
			@else
				@for(int i = 0; i < items.size(); i++)
				!{JoinRequest row = items.get(i);}
				<tr>
					<td>${i + 1}</td>
					<td class="bold">${row.label()}</td>
					<td>${row.phone() == null || row.phone().isBlank() ? "—" : row.phone()}</td>
					<td>
						<span class="badge ${"approved".equals(row.badge()) ? "badge-green"
							: "rejected".equals(row.badge()) ? "badge-red" : "badge-blue"}">
							${t.apply("approved".equals(row.badge()) ? "status_approved"
								: "rejected".equals(row.badge()) ? "status_rejected" : "status_pending")}
						</span>
					</td>
					<td>${row.createdAt() == null ? "—" : row.createdAt().toString().replace("T", " ")}</td>
					<td class="col-actions">
						<div class="actions">
						@if(row.pending() && canWrite)
							<form method="POST" class="inline-form" action="/admin/join_requests">
								<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
								<input type="hidden" name="action" value="accept_join">
								<input type="hidden" name="id" value="${row.id()}">
								<input type="hidden" name="redirect_status" value="${status}">
								<button type="submit" class="btn btn-green btn-sm">${t.apply("approve")}</button>
							</form>
							<%-- Rejecting deletes the employee row, so the confirm
							     is not decoration. --%>
							<form method="POST" class="inline-form" action="/admin/join_requests"
							      onsubmit="return confirm('${t.apply("confirm_action")}')">
								<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
								<input type="hidden" name="action" value="reject_join">
								<input type="hidden" name="id" value="${row.id()}">
								<input type="hidden" name="redirect_status" value="${status}">
								<button type="submit" class="btn btn-red btn-sm">${t.apply("reject")}</button>
							</form>
						@elseif(row.accepted())
							<a href="/admin/employees?action=edit&id=${row.id()}"
							   class="btn btn-blue btn-sm">${t.apply("edit")}</a>
						@else
							<span class="text-muted">—</span>
						@endif
						</div>
					</td>
				</tr>
				@endfor
			@endif
			</tbody>
		</table>
		</div>
	</div>
`)
