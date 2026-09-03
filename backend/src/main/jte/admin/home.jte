@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@template.admin.layout(
	title = "Overview",
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentAdminPhone = currentAdminPhone,
	body = @`
		<div class="card">
			<h1>Signed in</h1>
			<p class="sub">Platform-admin session established.</p>
			<dl class="facts">
				<dt>Administrator</dt><dd>${currentAdminPhone}</dd>
				<dt>Surface</dt><dd>Server-rendered, in-process (ADR-0015)</dd>
			</dl>
			<p class="notice">
				Company administration is not enabled on this surface yet. ADR-0015
				requires every implementation prerequisite to be met before it performs
				a privileged operation.
			</p>
		</div>
	`)
