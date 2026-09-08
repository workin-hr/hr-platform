@import gg.jte.Content
@import java.util.function.Function
@param String title
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone = null
@param String currentPage = ""
@param String lang = "ar"
@param com.workin.backend.platformadmin.web.DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
@param String flash = null
@param String flashType = "success"
@param java.util.List<String> pageStyles = java.util.List.of()
@param java.util.List<String> pageScripts = java.util.List.of()
<%-- The page's own wrapper class, beside the shared .content. Nearly every
     dashboard page uses hr-page, so that is the default; the two detail pages
     use a bare class="content" and pass "" for it. --%>
@param String contentClass = "hr-page"
@param Content body
<%-- The dashboard's shell, class for class (dashboard/includes/layout.php), so
     the stylesheets copied from it apply unchanged. Assets are served from
     /admin/_assets/**, the one prefix under /admin that is permitAll -- see
     PlatformAdminWebSecurityConfig, where the exception is stated and tested. --%>
<!DOCTYPE html>
<html lang="${lang}" dir="${"ar".equals(lang) ? "rtl" : "ltr"}">
<head>
	<meta charset="UTF-8">
	<meta name="viewport" content="width=device-width, initial-scale=1">
	<title>${title} — ${t.apply("app_name")}</title>
	<link rel="stylesheet" href="/admin/_assets/style.css">
	<link rel="stylesheet" href="/admin/_assets/app-ui.css">
	<link rel="stylesheet" href="/admin/_assets/sidebar.css">
	<%-- After the copied sheets, not before: the @font-face blocks would
	     work anywhere, but the family override is a `body` rule at the same
	     specificity as style.css's, so it only wins by coming later. --%>
	<link rel="stylesheet" href="/admin/_assets/fonts.css">
	<link rel="stylesheet" href="/admin/_assets/admin-extra.css">
	<%-- $pageStyles in dashboard/includes/layout.php: the per-page stylesheets,
	     appended after the shared ones so a page's own rules win. Naming them
	     on the template is what keeps a copied class name and the file that
	     styles it from drifting apart. --%>
@for(String pageStyle : pageStyles)
	<link rel="stylesheet" href="/admin/_assets/${pageStyle}">
@endfor
</head>
<body class="lang-${lang}">
@if(currentAdminPhone == null)
	<%-- The login page renders without the shell: there is no session
	     yet, so a sidebar would advertise pages the visitor cannot open. --%>
	<main class="auth-shell">
		${body}
	</main>
@else
	<div class="shell">
		@template.admin.sidebar(currentPage = currentPage, lang = lang, session = session,
				csrfParameterName = csrfParameterName, csrfToken = csrfToken, t = t,
				available = available)
		<main class="main">
			<div class="topbar">
				<h1 class="page-title">${title}</h1>
				<div class="topbar-util">
					<span class="who">${currentAdminPhone}</span>
				</div>
			</div>
			@if(flash != null)
				<div class="flash flash-${flashType}">${flash}</div>
			@endif
			<%-- .content is what the dashboard's stylesheets pad and scroll; a body
			     placed straight into .main overflows it (.main is overflow:hidden). --%>
			<div class="content${contentClass.isEmpty() ? "" : " " + contentClass}">
				${body}
			</div>
		</main>
	</div>
	<script src="/admin/_assets/sidebar.js"></script>
	<script src="/admin/_assets/row-actions.js"></script>
	<script src="/admin/_assets/crud.js"></script>
	<%-- Opens a row action's dialog and fills it from the row that asked.
	     Shared, because four list pages need one. --%>
	<script src="/admin/_assets/row-dialog.js"></script>
	<%-- Escape, a focus trap and focus restore for the copied modal,
	     which crud.js opens and closes with a class and nothing else. --%>
	<script src="/admin/_assets/modal-a11y.js"></script>
	<%-- Swaps a broken stored image for the initials placeholder it would have
	     had. Legacy checks the file server-side; the stored value here is a
	     URL, which may not be this application's to stat. --%>
	<script src="/admin/_assets/img-fallback.js"></script>
	<%-- $pageScripts in dashboard/includes/layout.php: after the shared ones,
	     so a page's script can rely on them having run. --%>
@for(String pageScript : pageScripts)
	<script src="/admin/_assets/${pageScript}"></script>
@endfor
@endif
</body>
</html>
