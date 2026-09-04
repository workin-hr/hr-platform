@import java.util.function.Function
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param boolean factorBound
@param String currentPage
@param String lang
@param boolean isAdmin
@param Function<String, String> t
@param Function<String, Boolean> available
@template.admin.layout(
	title = t.apply("nav_home"),
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentAdminPhone = currentAdminPhone,
	currentPage = currentPage,
	lang = lang,
	isAdmin = isAdmin,
	t = t,
	available = available,
	body = @`
		@if(!factorBound)
			<div class="home-panel">
				<h1>Set up two-factor authentication</h1>
				<p class="data-table-title">This account has no second factor bound.</p>
				<p class="flash flash-warning">
					Enrolment needs a bootstrap token issued to you out of band by
					another administrator, in addition to your password. Until the
					factor is bound, this account cannot perform administrative
					actions.
				</p>
			</div>
		@endif
		<div class="home-panel">
			<h1>Signed in</h1>
			<p class="data-table-title">Platform-admin session established.</p>
			<dl class="facts">
				<dt>Administrator</dt><dd>${currentAdminPhone}</dd>
				<dt>Second factor</dt><dd>${factorBound ? "bound" : "not bound"}</dd>
				<dt>Surface</dt><dd>Server-rendered, in-process (ADR-0015)</dd>
			</dl>
			<p class="flash flash-warning">
				Company administration is not enabled on this surface yet. ADR-0015
				requires every implementation prerequisite to be met before it performs
				a privileged operation.
			</p>
		</div>
	`)
