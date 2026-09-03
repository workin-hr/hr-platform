@param String csrfParameterName
@param String csrfToken
@param String error = null
@template.admin.layout(
	title = "Sign in",
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	body = @`
		<div class="login-wrap">
			<div class="card">
				<h1>Sign in</h1>
				<p class="sub">Platform administration for Workin itself.</p>
				@if(error != null)
					<p class="error">${error}</p>
				@endif
				<form method="post" action="/admin/login">
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
					<button type="submit" class="primary">Sign in</button>
				</form>
			</div>
		</div>
	`)
