@import java.util.List
@import java.util.SequencedMap
@import java.util.function.Function
@import com.workin.backend.platformadmin.home.HomeChart
@import com.workin.backend.platformadmin.content.Banner
@import com.workin.backend.platformadmin.home.HomeDisplay
@import com.workin.backend.platformadmin.home.HomeStore
@import com.workin.backend.platformadmin.home.HomeSummary
@import com.workin.backend.platformadmin.hr.Activity
@import com.workin.backend.platformadmin.hr.Complaint
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param String currentPage
@param String lang
@param com.workin.backend.platformadmin.web.DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
@param HomeSummary summary = null
@param SequencedMap<String, HomeChart> charts = null
@param List<Activity> activities = null
@param List<Complaint> complaints = null
@param HomeStore.Turnover turnover = null
@param List<Banner> banners = java.util.List.of()
<%-- Planned headcount and actual, over one label set. Two series, so they
     travel beside the single-series map rather than inside it. --%>
@param HomeChart planned = null
@param HomeChart actual = null
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

		<div class="home-welcome">
			<h2>${t.apply("welcome_greeting")}، ${currentAdminPhone} 👋</h2>
		</div>

		<%-- The banner carousel legacy's index.php opens with. home.css has
		     carried .home-banner-wrap and its eighteen sibling rules since the
		     stylesheets were copied; nothing used them, which is D-197's
		     finding on this page. The rows are the ones the banners page
		     administers, so an operator sees what the clients see. --%>
		@if(!banners.isEmpty())
			<div class="home-banner-wrap" id="bannerCarousel">
				<div class="home-banner-track" id="bannerTrack">
					@for(Banner banner : banners)
						!{Banner.Cta cta = banner.cta(lang);}
						<div class="home-banner-slide">
							@if(banner.imageUrl() != null && !banner.imageUrl().isBlank())
								<div class="home-banner-media">
									<img src="${banner.imageUrl()}" alt="${banner.title(lang)}">
									<div class="home-banner-overlay" aria-hidden="true"></div>
								</div>
							@endif
							<div class="home-banner-content">
								<h3>${banner.title(lang)}</h3>
								@if(!banner.description(lang).isEmpty())
									<p>${banner.description(lang)}</p>
								@endif
								@if(cta != null)
									@if(cta.external())
										<a href="${cta.href()}" class="home-banner-cta"
										   target="_blank" rel="noopener">${cta.label()}</a>
									@else
										<a href="${cta.href()}" class="home-banner-cta">${cta.label()}</a>
									@endif
								@endif
							</div>
						</div>
					@endfor
				</div>
				@if(banners.size() > 1)
					<div class="home-banner-dots" id="bannerDots">
						@for(int at = 0; at < banners.size(); at++)
							<button type="button" data-i="${at}"
							        class="${at == 0 ? "active" : ""}"
							        aria-label="${String.valueOf(at + 1)}"></button>
						@endfor
					</div>
				@endif
			</div>
		@endif

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

			<%-- The three rates. Percentages rather than counts, so they sit in
			     their own row: a rate beside sixteen headcounts reads as one
			     more headcount, which is how "2.01" gets mistaken for two
			     people. --%>
			@if(turnover != null)
				<%-- Legacy's order, icons and tones (index.php): monthly, annual,
				     then the 90-day cohort rate. --%>
				<div class="home-stats-grid home-stats-grid--rates">
					@template.admin.statCard(href = null, value = turnover.monthlyDisplay() + "%",
							label = t.apply("turnover_monthly"), icon = "📈", tone = "blue")
					@template.admin.statCard(href = null, value = turnover.annualDisplay() + "%",
							label = t.apply("turnover_annual"), icon = "📊", tone = "violet")
					@template.admin.statCard(href = null, value = turnover.newHiresDisplay() + "%",
							label = t.apply("turnover_new_3mo"), icon = "👤", tone = "yellow")
				</div>
			@endif

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
							<p>${t.apply("recent_activities_sub")}</p>
						</div>
					</div>
					<div class="home-panel-body">
						@if(activities == null || activities.isEmpty())
							<div class="home-panel-empty">${t.apply("no_data")}</div>
						@else
							<%-- Activity already carries home_present_activity(): the
							     badge, its class and the detail sentence. This panel was
							     rebuilding a worse version of all three -- "attendance"
							     for a badge that should say check-in or check-out, and
							     the employee code where the time of the punch goes. --%>
							@for(Activity row : activities)
								<div class="home-activity-row">
									<div class="home-activity-main">
										<div class="home-activity-line1">
											<strong>${row.name()}</strong>
											<span class="home-activity-badge ${row.badgeClass()}">${t.apply(row.badgeKey())}</span>
										</div>
										<p class="home-activity-detail">${row.detail(t, lang)}</p>
									</div>
									<span class="home-activity-side">${row.occurredAt() == null
										? "" : HomeDisplay.dateTime(row.occurredAt().toString())}</span>
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
							<p>${HomeDisplay.complaintsSubtitle(summary.openComplaints(),
								"ar".equals(lang), t.apply("employee_complaints_sub"))}</p>
						</div>
					</div>
					<div class="home-panel-body">
						@if(complaints == null || complaints.isEmpty())
							<div class="home-panel-empty">${t.apply("admin_complaints_empty_sub")}</div>
						@else
							<%-- .home-join-row, not .home-activity-row: a complaint is a
							     person to answer, and legacy gives it the avatar, the
							     company and the time, with WhatsApp and reply beside it.
							     Both class sets were copied; only one was used. --%>
							@for(Complaint row : complaints)
								!{String who = row.name() == null || row.name().isBlank()
										? (row.phone() == null || row.phone().isBlank() ? "—" : row.phone())
										: row.name();}
								!{String meta = HomeDisplay.dateTime(row.createdAt());}
								!{String company = row.companyName() == null ? "" : row.companyName();}
								!{String whatsapp = HomeDisplay.whatsapp(row.phone());}
								<div class="home-join-row">
									<div class="home-join-user">
										<div class="home-join-avatar">
											<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/></svg>
										</div>
										<div class="home-join-meta">
											<strong>${who}</strong>
											<span>${company.isEmpty() || company.equalsIgnoreCase(who)
												? meta : (meta.isEmpty() ? company : company + " · " + meta)}</span>
										</div>
									</div>
									<div class="home-join-actions">
										@if(whatsapp != null)
											<a class="home-join-btn home-join-btn--ok" href="${whatsapp}"
											   target="_blank" rel="noopener" title="WhatsApp">
												<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M17.472 14.382c-.297-.149-1.758-.867-2.03-.967-.273-.099-.471-.148-.67.15-.197.297-.767.966-.94 1.164-.173.199-.347.223-.644.075-.297-.15-1.255-.463-2.39-1.475-.883-.788-1.48-1.761-1.653-2.059-.173-.297-.018-.458.13-.606.134-.133.298-.347.446-.52.149-.174.198-.298.298-.497.099-.198.05-.371-.025-.52-.075-.149-.669-1.612-.916-2.207-.242-.579-.487-.5-.669-.51-.173-.008-.371-.01-.57-.01-.198 0-.52.074-.792.372-.272.297-1.04 1.016-1.04 2.479 0 1.462 1.065 2.875 1.213 3.074.149.198 2.096 3.2 5.077 4.487.709.306 1.262.489 1.694.625.712.227 1.36.195 1.871.118.571-.085 1.758-.719 2.006-1.413.248-.694.248-1.289.173-1.413-.074-.124-.272-.198-.57-.347m-5.421 7.403h-.004a9.87 9.87 0 01-5.031-1.378l-.361-.214-3.741.982.998-3.648-.235-.374a9.86 9.86 0 01-1.51-5.26c.001-5.45 4.436-9.884 9.888-9.884 2.64 0 5.122 1.03 6.988 2.898a9.825 9.825 0 012.893 6.994c-.003 5.45-4.435 9.884-9.885 9.884m8.413-18.297A11.815 11.815 0 0012.05 0C5.495 0 .16 5.335.157 11.892c0 2.096.547 4.142 1.588 5.945L.057 24l6.305-1.654a11.882 11.882 0 005.683 1.448h.005c6.554 0 11.89-5.335 11.893-11.893a11.821 11.821 0 00-3.48-8.413z"/></svg>
											</a>
										@endif
										<a class="home-join-btn" href="/admin/complaints?status=pending"
										   title="${t.apply("reply")}">
											<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
										</a>
									</div>
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
					<%-- The one chart with two series over one label set. Same
					     card, same script; a second values attribute and the
					     legend labels it needs. --%>
					@if(planned != null && !planned.isEmpty())
						<div class="home-chart-card wide"
								data-chart="bar"
								data-axis="x"
								data-labels="${planned.labelsJson()}"
								data-values="${planned.valuesJson()}"
								data-values2="${actual.valuesJson()}"
								data-series="${t.apply("planned")}"
								data-series2="${t.apply("actual")}">
							<h4>${t.apply("chart_workforce")}</h4>
							<div class="chart-box"><canvas></canvas></div>
						</div>
					@endif
				</div>
			@endif
	`)
