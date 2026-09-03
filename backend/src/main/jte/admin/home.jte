@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param boolean factorBound
@template.admin.layout(
	title = "Overview",
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentAdminPhone = currentAdminPhone,
	body = @`
		@if(!factorBound)
			<div class="card">
				<h1>Set up two-factor authentication</h1>
				<p class="sub">This account has no second factor bound.</p>
				<p class="notice">
					Enrolment needs a bootstrap token issued to you out of band by
					another administrator, in addition to your password. Until the
					factor is bound, this account cannot perform administrative
					actions.
				</p>
			</div>
		@endif
		<div class="card">
			<h1>Signed in</h1>
			<p class="sub">Platform-admin session established.</p>
			<dl class="facts">
				<dt>Administrator</dt><dd>${currentAdminPhone}</dd>
				<dt>Second factor</dt><dd>${factorBound ? "bound" : "not bound"}</dd>
				<dt>Surface</dt><dd>Server-rendered, in-process (ADR-0015)</dd>
			</dl>
			<p class="notice">
				Company administration is not enabled on this surface yet. ADR-0015
				requires every implementation prerequisite to be met before it performs
				a privileged operation.
			</p>
		</div>
	`)
