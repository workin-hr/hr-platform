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
		<div class="card">
			<h1>${t.apply("your_sessions")}</h1>
			<p class="sub">${t.apply("your_sessions_hint")}</p>
			<%-- The one table on this surface that was not in a scroll container:
			     three columns of timestamps overflow a phone, and without this
			     the revoke button is the part that goes off-screen. --%>
			<div class="table-wrap">
				<table class="grid">
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
										<button type="submit" class="linkish">${t.apply("revoke")}</button>
									</form>
								</td>
							</tr>
						@endfor
						@if(sessions.isEmpty())
							<tr><td colspan="3" class="empty">${t.apply("no_data")}</td></tr>
						@endif
					</tbody>
				</table>
			</div>
			<p class="notice">${t.apply("api_families_not_listed")}</p>
		</div>
	`)
