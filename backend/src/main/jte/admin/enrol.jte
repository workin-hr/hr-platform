@import java.util.function.Function
@param String csrfParameterName
@param String csrfToken
@param String error = null
@param String currentPage
@param String lang
@param com.workin.backend.platformadmin.web.DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
@template.admin.layout(
	title = "Set up two-factor",
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentPage = currentPage,
	lang = lang,
	session = session,
	t = t,
	available = available,
	body = @`
		<div class="login-wrap">
			<div class="card">
				<h1>Set up two-factor</h1>
				<p class="sub">
					Needs your password and the bootstrap token another administrator
					issued to you.
				</p>
				@if(error != null)
					<p class="error">${error}</p>
				@endif
				<form method="post" action="/admin/enrol">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<div class="field">
						<label for="phone">Phone</label>
						<input type="text" id="phone" name="phone" autocomplete="username"
							autocapitalize="off" autocorrect="off" required>
					</div>
					<div class="field">
						<label for="password">Password</label>
						<input type="password" id="password" name="password"
							autocomplete="current-password" required>
					</div>
					<div class="field">
						<label for="bootstrapToken">Bootstrap token</label>
						<input type="password" id="bootstrapToken" name="bootstrapToken"
							autocomplete="off" required>
					</div>
					<button type="submit" class="primary">Continue</button>
				</form>
			</div>
		</div>
	`)
