@import java.util.function.Function
@param String csrfParameterName
@param String csrfToken
@param String seed
@param String phone
@param String currentPage
@param String lang
@param com.workin.backend.platformadmin.web.DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
@template.admin.layout(
	title = "Confirm two-factor",
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
				<h1>Scan this once</h1>
				<p class="sub">
					Add it to your authenticator now. It is shown once and cannot be
					retrieved again.
				</p>
				<dl class="facts">
					<dt>Account</dt><dd>${phone}</dd>
					<dt>Key</dt><dd><code>${seed}</code></dd>
				</dl>
				<form method="post" action="/admin/enrol/confirm">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<div class="field">
						<label for="code">Code from your authenticator</label>
						<input type="text" id="code" name="code" inputmode="numeric"
							autocomplete="one-time-code" pattern="[0-9]*" maxlength="6"
							autocapitalize="off" autocorrect="off" autofocus required>
					</div>
					<button type="submit" class="primary">Bind this factor</button>
				</form>
				<p class="notice">
					The factor is not active until a code is accepted here.
				</p>
			</div>
		</div>
	`)
