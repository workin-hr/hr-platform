@import java.util.List
@import java.util.SequencedMap
@import java.util.function.Function
@import com.workin.backend.platformadmin.home.HomeChart
@import com.workin.backend.platformadmin.home.HomeSummary
@import com.workin.backend.platformadmin.hr.Activity
@import com.workin.backend.platformadmin.hr.Complaint
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param boolean factorBound
@param String currentPage
@param String lang
@param com.workin.backend.platformadmin.web.DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
<%-- Absent under the PostgreSQL profile, where there is no legacy database to
     summarise. The page then renders its header and the enrolment notice and
     stops, rather than the context failing to start. --%>
@param HomeSummary summary = null
@param SequencedMap<String, HomeChart> charts = null
@param List<Activity> activities = null
@param List<Complaint> complaints = null
<%-- dashboard/index.php. The class names are the dashboard's own
     (pages/home/assets/style.css, copied to home.css), so the layout is
     legacy's; what differs is the audience. Every block here is the
     administrator's half of a page legacy also serves to a company owner and
     to HR, and the numbers follow the session's company filter -- 0 meaning
     every company at once, which is the view only this surface has. --%>
@template.admin.layout(
	title = t.apply("nav_home"),
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentAdminPhone = currentAdminPhone,
	currentPage = currentPage,
	lang = lang,
	session = session,
	t = t,
	available = available,
	contentClass = "home-page",
	pageStyles = java.util.List.of("home.css"),
	pageScripts = java.util.List.of("chart.umd.js", "home.js"),
	body = @`
		@if(!factorBound)
			<div class="flash flash-warning">
				${t.apply("mfa_required_for_actions")}
			</div>
		@endif

		<div class="home-welcome">
			<h2>${t.apply("welcome_greeting")}، ${currentAdminPhone} 👋</h2>
		</div>

		@if(summary == null)
			<div class="home-panel">
				<div class="home-panel-body">
					<p class="data-table-title">${t.apply("no_data")}</p>
				</div>
			</div>
		@else
			<%-- The counts. Each card is a number and what it counts; the ones
			     that lead somewhere are links, because a number nobody can act
			     on is decoration. --%>
			<div class="home-stats-grid">
				@template.admin.statCard(href = "/admin/companies", value = String.valueOf(summary.companiesTotal()),
						label = t.apply("total_companies"), icon = "🏢", tone = "blue")
				@template.admin.statCard(href = "/admin/companies?filter=active", value = String.valueOf(summary.companiesActive()),
						label = t.apply("active_companies"), icon = "✓", tone = "green")
				@template.admin.statCard(href = "/admin/companies?filter=pending", value = String.valueOf(summary.companiesPending()),
						label = t.apply("pending_companies"), icon = "⏳", tone = "yellow")
				@template.admin.statCard(href = "/admin/employees", value = String.valueOf(summary.employeesTotal()),
						label = t.apply("total_employees"), icon = "👥", tone = "violet")
				@template.admin.statCard(href = "/admin/attendance", value = String.valueOf(summary.checkedInToday()),
						label = t.apply("checked_in_today"), icon = "📅", tone = "green")
				@template.admin.statCard(href = "/admin/requests?status=pending", value = String.valueOf(summary.pendingRequests()),
						label = t.apply("pending_requests"), icon = "⏱", tone = "yellow")
				@template.admin.statCard(href = "/admin/complaints", value = String.valueOf(summary.openComplaints()),
						label = t.apply("open_complaints"), icon = "💬", tone = "red")
				@template.admin.statCard(href = "/admin/advances?status=pending", value = String.valueOf(summary.pendingAdvances()),
						label = t.apply("pending_advances"), icon = "💰", tone = "yellow")
				@template.admin.statCard(href = "/admin/penalties", value = String.valueOf(summary.penaltiesTotal()),
						label = t.apply("total_penalties"), icon = "⚖", tone = "red")
				@template.admin.statCard(href = "/admin/penalties?applied=0", value = String.valueOf(summary.penaltiesUnapplied()),
						label = t.apply("unapplied_penalties"), icon = "!", tone = "red")
				@template.admin.statCard(href = "/admin/branches", value = String.valueOf(summary.branchesTotal()),
						label = t.apply("total_branches"), icon = "🏬", tone = "blue")
				@template.admin.statCard(href = "/admin/payroll", value = String.valueOf(summary.payrollDraft()),
						label = t.apply("payroll_draft"), icon = "🧾", tone = "yellow")
				@template.admin.statCard(href = null, value = summary.gross(),
						label = t.apply("total_gross_salaries"), icon = "💵", tone = "yellow")
				@template.admin.statCard(href = null, value = summary.basic(),
						label = t.apply("total_basic_salaries"), icon = "📊", tone = "violet")
				@template.admin.statCard(href = "/admin/payroll", value = summary.net(),
						label = t.apply("monthly_net"), icon = "✅", tone = "green")
				@template.admin.statCard(href = "/admin/employees?filter=inactive", value = String.valueOf(summary.resignations()),
						label = t.apply("resignations_year"), icon = "↩", tone = "gray")
			</div>

			<div class="home-panels">
				<%-- What happened, and what is waiting. The two panels legacy
				     puts side by side, in the same order. --%>
				<div class="home-panel">
					<div class="home-panel-head">
						<div class="home-panel-icon home-panel-icon--clock" aria-hidden="true">
							<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor"
									stroke-width="2"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>
						</div>
						<div class="home-panel-head-text">
							<h3>${t.apply("recent_activities")}</h3>
						</div>
					</div>
					<div class="home-panel-body">
						@if(activities == null || activities.isEmpty())
							<div class="home-panel-empty">${t.apply("no_data")}</div>
						@else
							@for(Activity row : activities)
								<div class="home-activity-row">
									<div class="home-activity-main">
										<div class="home-activity-line1">
											<strong>${row.employeeName()}</strong>
											<span class="home-activity-badge ${row.attendance() ? "badge-blue" : "badge-yellow"}">
												${t.apply(row.attendance() ? "attendance" : "requests")}</span>
										</div>
										<p class="home-activity-detail">${row.empCode()}</p>
									</div>
									<span class="home-activity-side">${row.at() == null ? "" : row.at().toString().replace("T", " ")}</span>
								</div>
							@endfor
						@endif
					</div>
					<div class="home-panel-footer">
						<a href="/admin/activities">${t.apply("view_all")}</a>
					</div>
				</div>

				<div class="home-panel">
					<div class="home-panel-head">
						<div class="home-panel-icon home-panel-icon--people" aria-hidden="true">
							<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor"
									stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
						</div>
						<div class="home-panel-head-text">
							<h3>${t.apply("nav_complaints_admin")}</h3>
						</div>
					</div>
					<div class="home-panel-body">
						@if(complaints == null || complaints.isEmpty())
							<div class="home-panel-empty">${t.apply("admin_complaints_empty_sub")}</div>
						@else
							@for(Complaint row : complaints)
								<div class="home-activity-row">
									<div class="home-activity-main">
										<div class="home-activity-line1">
											<strong>${row.name() == null || row.name().isEmpty() ? row.phone() : row.name()}</strong>
											<span class="home-activity-badge badge-yellow">${t.apply("status_pending")}</span>
										</div>
										<p class="home-activity-detail">${row.message()}</p>
									</div>
									<span class="home-activity-side">${row.createdDate()}</span>
								</div>
							@endfor
						@endif
					</div>
					<div class="home-panel-footer">
						<a href="/admin/complaints?status=pending">${t.apply("view_all_admin_complaints")}</a>
					</div>
				</div>
			</div>

			@if(charts != null && !charts.isEmpty())
				<%-- Series travel on data- attributes, not in an inline script:
				     JTE escapes an attribute, so a company named "</script>" is
				     a string in a JSON array and nothing else. --%>
				<div class="home-charts-grid">
					@for(java.util.Map.Entry<String, HomeChart> entry : charts.entrySet())
						!{String key = entry.getKey();}
						!{String kind = key.equals("chart_gender") ? "doughnut"
								: (key.equals("chart_new_monthly") || key.equals("chart_daily_att") ? "line" : "bar");}
						!{boolean wide = key.equals("chart_new_monthly") || key.equals("chart_salary_dept");}
						!{boolean horizontal = key.equals("chart_emp_dept") || key.equals("chart_emp_branch")
								|| key.equals("chart_salary_dept") || key.equals("chart_age");}
						<div class="home-chart-card${wide ? " wide" : ""}"
								data-chart="${kind}"
								data-axis="${horizontal ? "y" : "x"}"
								data-labels="${entry.getValue().labelsJson()}"
								data-values="${entry.getValue().valuesJson()}">
							<h4>${t.apply(key)}</h4>
							<div class="chart-box"><canvas></canvas></div>
						</div>
					@endfor
				</div>
			@endif
		@endif
	`)
