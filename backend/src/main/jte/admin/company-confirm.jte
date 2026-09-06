@import java.util.function.Function
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param String action
@param long companyId
@param String reason
@param String approvalId = null
@param String error = null
@param String currentPage
@param String lang
@param com.workin.backend.platformadmin.web.DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
@template.admin.layout(
	title = "Confirm action",
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentAdminPhone = currentAdminPhone,
	currentPage = currentPage,
	lang = lang,
	session = session,
	t = t,
	available = available,
	body = @`
		<div class="login-wrap">
			<div class="card">
				<h1>Confirm</h1>
				<p class="sub">This action needs your authenticator.</p>
				@if(error != null)
					<p class="error">${error}</p>
				@endif
				<dl class="facts">
					<dt>Action</dt><dd>${action}</dd>
					<dt>Company</dt><dd>#${companyId}</dd>
				</dl>
				@if(approvalId == null)
					<form method="post" action="/admin/companies/confirm">
						<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
						<input type="hidden" name="action" value="${action}">
						<input type="hidden" name="companyId" value="${companyId}">
						<div class="field">
							<label for="reason">Reason</label>
							<input type="text" id="reason" name="reason" value="${reason}" required>
						</div>
						<div class="field">
							<label for="code">Authenticator code</label>
							<input type="text" id="code" name="code" inputmode="numeric"
								autocomplete="one-time-code" pattern="[0-9]*" maxlength="6" required>
						</div>
						<button type="submit" class="primary">Authorise</button>
					</form>
				@else
					<form method="post" action="/admin/companies/apply">
						<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
						<input type="hidden" name="action" value="${action}">
						<input type="hidden" name="companyId" value="${companyId}">
						<input type="hidden" name="reason" value="${reason}">
						<input type="hidden" name="approvalId" value="${approvalId}">
						<p class="notice">Authorised. This approval is valid once, for this company and reason only.</p>
						<button type="submit" class="primary">Apply</button>
					</form>
				@endif
			</div>
		</div>
	`)
