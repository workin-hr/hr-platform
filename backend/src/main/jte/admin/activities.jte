@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.hr.Activity
@import com.workin.backend.platformadmin.web.DashboardListFilters
@import com.workin.backend.platformadmin.web.DashboardSession
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param List<Activity> rows
@param DashboardListFilters filters
@param String kind
@param List<String> kinds
@param boolean canAttendance
@param boolean canRequests
@param String dateFrom
@param String dateTo
@param int total
@param int pageNumber
@param int pages
@param int perPage
@param int rangeFrom
@param int rangeTo
@param String currentPage
@param String lang
@param DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
<%-- dashboard/pages/activities/page.php. A read-only feed: two tables joined
     by a UNION, so every row carries the columns of whichever side it came
     from and nulls for the other. --%>
@template.admin.layout(
	title = t.apply("nav_activities"),
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentAdminPhone = currentAdminPhone,
	currentPage = currentPage,
	lang = lang,
	session = session,
	t = t,
	available = available,
	pageStyles = java.util.List.of("hr-pages.css"),
	contentClass = "hr-page activities-page",
	body = @`
	<div class="page-toolbar page-toolbar-filters">
		<form method="GET" class="toolbar-form toolbar-form--labeled">
			@if(!session.isScopedToOneCompany())
				<div class="filter-field">
					<label class="filter-field__label" for="act_company">${t.apply("company")}</label>
					<input type="number" id="act_company" name="company_id" min="0"
					       value="${filters.companyId() > 0 ? String.valueOf(filters.companyId()) : ""}">
				</div>
			@endif
			<div class="filter-field">
				<label class="filter-field__label" for="activities-kind">${t.apply("activity_kind")}</label>
				<%-- Each option is offered only to a session that may see that
				     half of the feed, as legacy gates them. --%>
				<select id="activities-kind" name="kind">
					<option value="all" selected="${"all".equals(kind)}">${t.apply("filter_all")}</option>
					@if(canAttendance)
						<option value="attendance" selected="${"attendance".equals(kind)}">${t.apply("filter_attendance")}</option>
					@endif
					@if(canRequests)
						<option value="request" selected="${"request".equals(kind)}">${t.apply("filter_requests")}</option>
					@endif
				</select>
			</div>
			<div class="filter-field">
				<label class="filter-field__label" for="activities-from">${t.apply("from_date")}</label>
				<input type="date" id="activities-from" name="date_from" value="${dateFrom}">
			</div>
			<div class="filter-field">
				<label class="filter-field__label" for="activities-to">${t.apply("to_date")}</label>
				<input type="date" id="activities-to" name="date_to" value="${dateTo}">
			</div>
			<button type="submit" class="btn btn-blue">${t.apply("search")}</button>
		</form>
	</div>

	<div class="data-table-card">
		<div class="data-table-head">
			<h2 class="data-table-title">${t.apply("nav_activities")} (${total})</h2>
		</div>
		<div class="data-table-wrap">
		<table class="data-table">
			<thead>
				<tr>
					<th>#</th>
					<th>${t.apply("emp_code")}</th>
					<th>${t.apply("employee_name")}</th>
					<th class="col-center">${t.apply("activity_kind")}</th>
					<th>${t.apply("activity_description")}</th>
					<th class="col-datetime">${t.apply("activity_datetime")}</th>
				</tr>
			</thead>
			<tbody>
			@if(rows.isEmpty())
				<tr><td colspan="6" class="text-muted">—</td></tr>
			@else
				@for(int i = 0; i < rows.size(); i++)
				!{Activity row = rows.get(i);}
				<tr>
					<td>${rangeFrom + i}</td>
					<td class="text-muted" dir="ltr">${row.code()}</td>
					<td class="bold">${row.name()}</td>
					<td class="col-center">
						<span class="hr-activity-badge ${row.badgeClass()}">${t.apply(row.badgeKey())}</span>
					</td>
					<td class="text-muted col-desc" title="${row.detail(t, lang)}">${row.detail(t, lang)}</td>
					<td class="col-datetime" dir="ltr">${row.atCell()}</td>
				</tr>
				@endfor
			@endif
			</tbody>
		</table>
		</div>
	</div>

	@if(pages > 1)
		<%-- Numbered links, as every other list page here renders them. The
		     filters ride along so paging does not silently widen the query. --%>
		!{String tail = "&kind=" + kind + "&date_from=" + dateFrom + "&date_to=" + dateTo
			+ "&per_page=" + perPage
			+ (filters.companyId() > 0 ? "&company_id=" + filters.companyId() : "");}
		<div class="pager">
			@for(int p = 1; p <= pages; p++)
				<a href="/admin/activities?page=${p}${tail}"
				   class="pager__link${p == pageNumber ? " is-current" : ""}">${p}</a>
			@endfor
		</div>
	@endif
`)
