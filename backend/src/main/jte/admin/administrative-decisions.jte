@import java.util.function.Function
@import com.workin.backend.platformadmin.hr.AdministrativeDecision
@import com.workin.backend.platformadmin.web.DashboardListFilters
@import com.workin.backend.platformadmin.web.DashboardPage
@import com.workin.backend.platformadmin.web.DashboardSession
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param DashboardPage<AdministrativeDecision> result
@param DashboardListFilters filters
@param boolean showCompanyColumn
@param boolean canManage
@param boolean actionsEnabled
@param boolean factorBound
@param AdministrativeDecision editRow = null
@param boolean addOpen = false
@param String errorKey = null
@param String currentPage
@param String lang
@param DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
!{boolean canWrite = canManage && actionsEnabled && factorBound;}
!{int colCount = showCompanyColumn ? 6 : 5;}
<%-- dashboard/pages/administrative_decisions/page.php. --%>
@template.admin.layout(
	title = t.apply("nav_administrative_decisions"),
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
						<label class="filter-field__label" for="dc_company">${t.apply("company")}</label>
						<input type="number" id="dc_company" name="company_id" min="0"
						       placeholder="${t.apply("filter_all")}"
						       value="${filters.companyId() > 0 ? String.valueOf(filters.companyId()) : ""}">
					</div>
				@endif
				<div class="filter-field">
					<label class="filter-field__label" for="dc_search">${t.apply("search")}</label>
					<input type="search" id="dc_search" name="search"
					       placeholder="${t.apply("administrative_decisions_search_placeholder")}"
					       value="${filters.search()}">
				</div>
				<button type="submit" class="btn btn-blue">${t.apply("search")}</button>
			</form>
		</div>

		@if(addOpen || editRow != null)
			<div class="data-table-card">
				<div class="data-table-head">
					<h2 class="data-table-title">${t.apply(editRow == null
						? "add_administrative_decision" : "edit_administrative_decision")}</h2>
				</div>
				<form method="POST" class="account-form org-form">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action"
					       value="${editRow == null ? "add_decision" : "edit_decision"}">
					@if(editRow != null)
						<input type="hidden" name="id" value="${editRow.id()}">
						<%-- No company_id field on an edit. The row already has a
						     tenant and D-176 keeps it out of the request entirely:
						     legacy carried it here and wrote it back, which is how an
						     unfiltered administrator could move a decision between
						     companies (R-047). --%>
					@else
						<input type="hidden" name="company_id" value="${filters.companyId()}">
					@endif

					<div class="form-row">
						<label for="title">${t.apply("decision_title")}</label>
						<input type="text" id="title" name="title" required maxlength="255"
						       value="${editRow == null ? "" : editRow.title()}">
					</div>
					<div class="form-row">
						<label for="body">${t.apply("decision_body")}</label>
						<textarea id="body" name="body" required rows="6">${editRow == null ? "" : editRow.body()}</textarea>
					</div>
					<div class="form-row">
						<label for="is_active">${t.apply("status")}</label>
						<%-- PHP reads this with !empty(), so an absent field means
						     inactive here -- the opposite default from the org pages.
						     (No backticks in a JTE comment: the surrounding content
						     block is delimited by one.) --%>
						<select id="is_active" name="is_active">
							<option value="1" selected="${editRow != null && editRow.active()}">${t.apply("decision_status_active")}</option>
							<option value="0" selected="${editRow == null || !editRow.active()}">${t.apply("decision_status_inactive")}</option>
						</select>
					</div>
					<div class="account-form__footer">
						<a href="/admin/administrative_decisions" class="btn btn-gray">${t.apply("cancel")}</a>
						@if(canWrite)
							<button type="submit" class="btn btn-yellow">${t.apply("save")}</button>
						@endif
					</div>
				</form>
			</div>
		@endif

		<div class="data-table-card">
			<div class="data-table-head">
				<h2 class="data-table-title">${t.apply("nav_administrative_decisions")} (${result.total()})</h2>
				@if(canManage)
					<a href="/admin/administrative_decisions?action=add" class="btn btn-blue">
						+ ${t.apply("add_administrative_decision")}</a>
				@endif
			</div>
			<div class="data-table-wrap">
			<table class="data-table">
				<thead>
					<tr>
						<th>#</th>
						@if(showCompanyColumn)<th>${t.apply("company")}</th>@endif
						<th>${t.apply("decision_title")}</th>
						<th>${t.apply("status")}</th>
						<th>${t.apply("reg_date")}</th>
						<th>${t.apply("actions")}</th>
					</tr>
				</thead>
				<tbody>
					@for(int index = 0; index < result.data().size(); index++)
						!{AdministrativeDecision row = result.data().get(index);}
						<tr>
							<td>${result.from() + index}</td>
							@if(showCompanyColumn)
								<%-- A LEFT JOIN, so a decision whose company row is gone
								     still lists, with no name. --%>
								<td class="text-muted">${row.companyName() == null ? "—" : row.companyName()}</td>
							@endif
							<td class="bold">${row.title()}</td>
							<td>
								<span class="badge ${row.active() ? "badge-green" : "badge-gray"}">
									${t.apply(row.active() ? "decision_status_active" : "decision_status_inactive")}</span>
							</td>
							<td>${row.createdDate()}</td>
							<td class="col-actions">
								@if(canManage)
									<a href="/admin/administrative_decisions?action=edit&id=${row.id()}"
									   class="btn btn-outline btn-sm">${t.apply("edit")}</a>
									@if(canWrite)
										<form method="POST" class="inline-form"
										      onsubmit="return confirm('${t.apply("confirm_delete")}')">
											<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
											<input type="hidden" name="action" value="delete_decision">
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
						<tr><td colspan="${colCount}" class="data-table-empty">${t.apply("no_data")}</td></tr>
					@endif
				</tbody>
			</table>
			</div>
			@if(result.pages() > 1)
				<div class="pager">
					@for(int page = 1; page <= result.pages(); page++)
						<a href="/admin/administrative_decisions?page=${page}${filters.asQueryTail()}"
						   class="pager__link${page == result.page() ? " is-current" : ""}">${page}</a>
					@endfor
				</div>
			@endif
		</div>
	`)
