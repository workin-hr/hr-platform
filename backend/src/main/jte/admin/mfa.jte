@param String csrfParameterName
@param String csrfToken
@param String error = null
@template.admin.layout(
	title = "Two-factor code",
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	body = @`
		<div class="login-wrap">
			<div class="card">
				<h1>Two-factor code</h1>
				<p class="sub">Enter the six-digit code from your authenticator.</p>
				@if(error != null)
					<p class="error">${error}</p>
				@endif
				<form method="post" action="/admin/mfa">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<div class="field">
						<label for="code">Code</label>
						<input type="text" id="code" name="code" inputmode="numeric"
							autocomplete="one-time-code" pattern="[0-9]*" maxlength="6"
							autocapitalize="off" autocorrect="off" autofocus required>
					</div>
					<button type="submit" class="primary">Verify</button>
				</form>
			</div>
		</div>
	`)
