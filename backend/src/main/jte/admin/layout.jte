@import gg.jte.Content
@import java.util.function.Function
@param String title
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone = null
@param String currentPage = ""
@param String lang = "ar"
@param boolean isAdmin = true
@param Function<String, String> t
@param Function<String, Boolean> available
@param String flash = null
@param String flashType = "success"
@param Content body
<%-- The dashboard's shell, class for class (dashboard/includes/layout.php), so
     the stylesheets copied from it apply unchanged. Assets are served from
     /admin/assets/**, the one prefix under /admin that is permitAll -- see
     PlatformAdminWebSecurityConfig, where the exception is stated and tested. --%>
<!DOCTYPE html>
<html lang="${lang}" dir="${"ar".equals(lang) ? "rtl" : "ltr"}">
<head>
	<meta charset="UTF-8">
	<meta name="viewport" content="width=device-width, initial-scale=1">
	<title>${title} — ${t.apply("app_name")}</title>
	<link rel="stylesheet" href="/admin/assets/style.css">
	<link rel="stylesheet" href="/admin/assets/app-ui.css">
	<link rel="stylesheet" href="/admin/assets/sidebar.css">
	<link rel="stylesheet" href="/admin/assets/admin-extra.css">
</head>
<body class="lang-${lang}">
@if(currentAdminPhone == null)
	<%-- Login, MFA and enrolment render without the shell: there is no session
	     yet, so a sidebar would advertise pages the visitor cannot open. --%>
	<main class="auth-shell">
		${body}
	</main>
@else
	<div class="shell">
		@template.admin.sidebar(currentPage = currentPage, lang = lang, isAdmin = isAdmin,
				csrfParameterName = csrfParameterName, csrfToken = csrfToken, t = t,
				available = available)
		<main class="main">
			<div class="topbar">
				<h1 class="page-title">${title}</h1>
				<span class="who">${currentAdminPhone}</span>
			</div>
			@if(flash != null)
				<div class="flash flash-${flashType}">${flash}</div>
			@endif
			<%-- .content is what the dashboard's stylesheets pad and scroll; a body
			     placed straight into .main overflows it (.main is overflow:hidden). --%>
			<div class="content hr-page">
				${body}
			</div>
		</main>
	</div>
	<script src="/admin/assets/sidebar.js"></script>
	<script src="/admin/assets/row-actions.js"></script>
	<script src="/admin/assets/crud.js"></script>
@endif
</body>
</html>
