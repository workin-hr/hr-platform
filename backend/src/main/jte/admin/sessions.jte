@import java.util.List
@import com.workin.backend.platformadmin.web.PlatformAdminSessionInventory.BrowserSession
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param List<BrowserSession> sessions
@template.admin.layout(
	title = "Sessions",
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentAdminPhone = currentAdminPhone,
	body = @`
		<div class="card">
			<h1>Your sessions</h1>
			<p class="sub">Browser sessions signed in as you. Revoke any you do not recognise.</p>
			<table class="grid">
				<thead>
					<tr><th>Started</th><th>Last used</th><th></th></tr>
				</thead>
				<tbody>
					@for(BrowserSession session : sessions)
						<tr>
							<td>${session.createdAt().toString()}</td>
							<td>${session.lastAccessedAt().toString()}
								@if(session.current())<span class="badge">this one</span>@endif
							</td>
							<td>
								<form method="post" action="/admin/sessions/revoke">
									<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
									<input type="hidden" name="sessionId" value="${session.id()}">
									<button type="submit" class="linkish">Revoke</button>
								</form>
							</td>
						</tr>
					@endfor
				</tbody>
			</table>
			<p class="notice">
				API token families are separate and are not listed here; revoking a
				browser session does not end them.
			</p>
		</div>
	`)
