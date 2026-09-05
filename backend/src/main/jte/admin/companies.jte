@import java.util.List
@import com.workin.backend.platformadmin.PlatformAdminCompanyDirectory.CompanyView
@import java.util.function.Function
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param boolean actionsEnabled
@param List<CompanyView> companies
@param String error = null
@param String currentPage
@param String lang
@param com.workin.backend.platformadmin.web.DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
<%-- Table markup follows dashboard/pages/companies/page.php: .data-table-card
     wrapping .data-table-head and a .table-wrap > table.tbl, so the copied
     stylesheets style it. The status pill uses the dashboard's own badge
     classes rather than a new one. --%>
@template.admin.layout(
	title = t.apply("nav_companies"),
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentAdminPhone = currentAdminPhone,
	currentPage = currentPage,
	lang = lang,
	session = session,
	t = t,
	available = available,
	body = @`
		@if(error != null)
			<div class="flash flash-error">${error}</div>
		@endif
		@if(!actionsEnabled)
			<div class="flash flash-warning">${t.apply("admin_actions_disabled")}</div>
		@endif
		<div class="data-table-card">
			<div class="data-table-head">
				<h2 class="data-table-title">${t.apply("nav_companies")} (${companies.size()})</h2>
			</div>
			<div class="table-wrap">
				<table class="tbl">
					<thead>
						<tr>
							<th>#</th>
							<th>${t.apply("company_name")}</th>
							<th>${t.apply("status")}</th>
							<th class="col-actions"><span class="sr-only">${t.apply("actions")}</span></th>
						</tr>
					</thead>
					<tbody>
						@for(CompanyView company : companies)
							<tr>
								<td>${company.id()}</td>
								<td>${company.name() == null ? "—" : company.name()}</td>
								<td>
									<span class="badge ${"active".equals(company.status()) ? "badge-green"
											: "pending".equals(company.status()) ? "badge-yellow"
											: "rejected".equals(company.status()) ? "badge-red" : "badge-gray"}">
										${t.apply("status_" + company.status())}
									</span>
								</td>
								<td class="col-actions">
									<%-- The dashboard's kebab menu (layout.php row_actions_menu):
									     .row-actions with a trigger and a menu of links and
									     single-action forms. row-actions.js, copied alongside,
									     opens it. Inline buttons in this cell blow the column
									     out -- the copied CSS keeps it narrow for exactly this. --%>
									<div class="row-actions" data-row-actions>
										<button type="button" class="row-actions__trigger" aria-label="${t.apply("actions")}"
										        aria-haspopup="menu" aria-expanded="false">
											<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
												<circle cx="12" cy="5" r="1.75"/><circle cx="12" cy="12" r="1.75"/><circle cx="12" cy="19" r="1.75"/>
											</svg>
										</button>
										<div class="row-actions__menu" role="menu">
											<a href="/admin/companies/${company.id()}" class="row-actions__item" role="menuitem">${t.apply("details")}</a>
											@if(actionsEnabled)
												<form method="post" action="/admin/companies/confirm" class="row-actions__form" role="none">
													<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
													<input type="hidden" name="companyId" value="${company.id()}">
													@if("pending".equals(company.status()))
														<button type="submit" name="action" value="COMPANY_APPROVE" class="row-actions__item" role="menuitem">${t.apply("approve")}</button>
														<button type="submit" name="action" value="COMPANY_REJECT" class="row-actions__item row-actions__item--danger" role="menuitem">${t.apply("reject")}</button>
													@elseif("suspended".equals(company.status()))
														<button type="submit" name="action" value="COMPANY_RESTORE" class="row-actions__item" role="menuitem">${t.apply("restore")}</button>
													@elseif("rejected".equals(company.status()))
														<button type="submit" name="action" value="COMPANY_APPROVE" class="row-actions__item" role="menuitem">${t.apply("approve")}</button>
													@else
														<button type="submit" name="action" value="COMPANY_SUSPEND" class="row-actions__item row-actions__item--danger" role="menuitem">${t.apply("suspend")}</button>
													@endif
												</form>
											@endif
										</div>
									</div>
								</td>
							</tr>
						@endfor
						@if(companies.isEmpty())
							<tr><td colspan="4" class="data-table-empty">${t.apply("no_data")}</td></tr>
						@endif
					</tbody>
				</table>
			</div>
		</div>
	`)
