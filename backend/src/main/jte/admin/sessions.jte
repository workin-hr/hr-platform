@import java.util.List
@import com.workin.backend.platformadmin.web.PlatformAdminSessionInventory.BrowserSession
@import java.util.function.Function
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param List<BrowserSession> sessions
@param String currentPage
@param String lang
@param com.workin.backend.platformadmin.web.DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
@template.admin.layout(
	title = t.apply("your_sessions"),
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentAdminPhone = currentAdminPhone,
	currentPage = currentPage,
	lang = lang,
	session = session,
	t = t,
	available = available,
	body = @`
		<%-- No legacy equivalent, so this borrows the shared vocabulary rather
		     than inventing one: card/grid/linkish/notice/sub were defined by no
		     stylesheet here or in legacy, and the page rendered unstyled. --%>
		<div class="data-table-card">
			<div class="data-table-head">
				<h2 class="data-table-title">${t.apply("your_sessions")}</h2>
			</div>
			<p class="text-muted">${t.apply("your_sessions_hint")}</p>
			<div class="table-wrap">
				<table class="tbl">
					<thead>
						<tr><th>${t.apply("started")}</th><th>${t.apply("last_used")}</th><th></th></tr>
					</thead>
					<tbody>
						@for(BrowserSession session : sessions)
							<tr>
								<td>${session.createdAt().toString()}</td>
								<td>${session.lastAccessedAt().toString()}
									@if(session.current())<span class="badge">${t.apply("this_session")}</span>@endif
								</td>
								<td>
									<form method="post" action="/admin/sessions/revoke">
										<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
										<input type="hidden" name="sessionId" value="${session.id()}">
										<button type="submit" class="btn btn-red btn-sm">${t.apply("revoke")}</button>
									</form>
								</td>
							</tr>
						@endfor
						@if(sessions.isEmpty())
							<tr><td colspan="3" class="text-muted">${t.apply("no_data")}</td></tr>
						@endif
					</tbody>
				</table>
			</div>
			<p class="text-muted">${t.apply("api_families_not_listed")}</p>
		</div>
	`)
