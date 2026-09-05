@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.hr.CompanyAsset
@import com.workin.backend.platformadmin.web.DashboardListFilters
@import com.workin.backend.platformadmin.web.DashboardPage
@import com.workin.backend.platformadmin.web.DashboardSession
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param DashboardPage<CompanyAsset> result
@param DashboardListFilters filters
@param List<com.workin.backend.platformadmin.hr.LeaveBalance.EmployeeOption> employeeOptions
@param String returned
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
!{int colCount = showCompanyColumn ? 9 : 8;}
<%-- dashboard/pages/assets/page.php. --%>
@template.admin.layout(
	title = t.apply("nav_assets"),
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
						<label class="filter-field__label" for="as_company">${t.apply("company")}</label>
						<input type="number" id="as_company" name="company_id" min="0"
						       placeholder="${t.apply("filter_all")}"
						       value="${filters.companyId() > 0 ? String.valueOf(filters.companyId()) : ""}">
					</div>
				@endif
				<div class="filter-field">
					<label class="filter-field__label" for="as_search">${t.apply("search")}</label>
					<input type="search" id="as_search" name="search"
					       placeholder="${t.apply("assets_search_placeholder")}"
					       value="${filters.search()}">
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="as_returned">${t.apply("is_returned")}</label>
					<select id="as_returned" name="returned">
						<option value="all" selected="${"all".equals(returned)}">${t.apply("filter_all")}</option>
						<option value="0" selected="${"0".equals(returned)}">${t.apply("no")}</option>
						<option value="1" selected="${"1".equals(returned)}">${t.apply("yes")}</option>
					</select>
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="as_from">${t.apply("from_date")}</label>
					<input type="date" id="as_from" name="date_from" value="${dateFrom}">
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="as_to">${t.apply("to_date")}</label>
					<input type="date" id="as_to" name="date_to" value="${dateTo}">
				</div>
				<button type="submit" class="btn btn-blue">${t.apply("search")}</button>
			</form>
		</div>

		@if(canWrite)
			<div class="data-table-card">
				<div class="data-table-head">
					<h2 class="data-table-title">${t.apply("add_asset")}</h2>
				</div>
				<form method="POST" class="account-form org-form">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="add_asset">
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
						<label for="asset_text">${t.apply("asset")}</label>
						<input type="text" id="asset_text" name="asset_text" required maxlength="500">
					</div>
					<div class="form-row">
						<label for="asset_date">${t.apply("asset_date")}</label>
						<input type="date" id="asset_date" name="asset_date">
					</div>
					<div class="form-row">
						<label for="asset_end_date">${t.apply("asset_end_date")}</label>
						<input type="date" id="asset_end_date" name="asset_end_date">
					</div>
					<div class="account-form__footer">
						<button type="submit" class="btn btn-yellow">${t.apply("save")}</button>
					</div>
				</form>
			</div>
		@endif

		<div class="data-table-card">
			<div class="data-table-head">
				<h2 class="data-table-title">${t.apply("nav_assets")} (${result.total()})</h2>
			</div>
			<div class="data-table-wrap">
			<table class="data-table">
				<thead>
					<tr>
						<th>#</th>
						<th>${t.apply("emp_code")}</th>
						<th>${t.apply("employee_name")}</th>
						@if(showCompanyColumn)<th>${t.apply("company")}</th>@endif
						<th>${t.apply("asset")}</th>
						<th>${t.apply("asset_date")}</th>
						<th>${t.apply("asset_end_date")}</th>
						<th>${t.apply("is_returned")}</th>
						<th>${t.apply("actions")}</th>
					</tr>
				</thead>
				<tbody>
					@for(int index = 0; index < result.data().size(); index++)
						!{CompanyAsset row = result.data().get(index);}
						<tr>
							<td>${result.from() + index}</td>
							<td class="text-muted">${row.employeeCode() == null ? "—" : row.employeeCode()}</td>
							<td class="bold">${row.employeeName()}</td>
							@if(showCompanyColumn)
								<td class="text-muted">${row.companyName() == null ? "—" : row.companyName()}</td>
							@endif
							<td class="bold">${row.assetText()}</td>
							<td>${row.assetDate() == null ? "—" : row.assetDate()}</td>
							<td>${row.assetEndDate() == null ? "—" : row.assetEndDate()}</td>
							<td>
								<span class="badge ${row.returned() ? "badge-green" : "badge-gray"}">
									${t.apply(row.returned() ? "yes" : "no")}</span>
							</td>
							<td class="col-actions">
								@if(canWrite && !row.returned())
									<%-- Once the asset is back the row is frozen against
									     editing: there is nothing left to correct about the
									     loan. --%>
									<form method="POST" class="inline-form">
										<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
										<input type="hidden" name="action" value="mark_returned">
										<input type="hidden" name="id" value="${row.id()}">
										<button type="submit" class="btn btn-outline btn-sm">${t.apply("is_returned")}</button>
									</form>
								@endif
								@if(canWrite)
									<form method="POST" class="inline-form"
									      onsubmit="return confirm('${t.apply("confirm_delete")}')">
										<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
										<input type="hidden" name="action" value="delete_asset">
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
						<a href="/admin/assets?page=${page}&returned=${returned}${filters.asQueryTail()}"
						   class="pager__link${page == result.page() ? " is-current" : ""}">${page}</a>
					@endfor
				</div>
			@endif
		</div>
	`)
