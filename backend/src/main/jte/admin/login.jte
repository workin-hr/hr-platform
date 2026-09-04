@import java.util.function.Function
@param String csrfParameterName
@param String csrfToken
@param String error = null
@param String lang
@param Function<String, String> t
<%-- Standalone, not through admin/layout: the dashboard's login has its own
     full-page design (hero panel beside the card) rather than the signed-in
     shell, and its stylesheet is the one copied to /admin/assets/login.css.
     Markup follows hr-legacy/dashboard/pages/login/page.php class for class.

     One deliberate difference: PHP's admin tab hides the phone field, because
     legacy authenticates the platform admin with a single shared password
     (hr-legacy#11). This surface has individual administrators (F-26 / D-027),
     so phone is shown and required. The company and HR tabs are absent until
     those sessions exist here (ADR-0016). --%>
<!DOCTYPE html>
<html lang="${lang}" dir="${"ar".equals(lang) ? "rtl" : "ltr"}">
<head>
	<meta charset="UTF-8">
	<meta name="viewport" content="width=device-width, initial-scale=1">
	<title>${t.apply("login_btn")} — ${t.apply("app_name")}</title>
	<link rel="stylesheet" href="/admin/assets/login.css">
</head>
<body class="login-body${error != null ? " login-shake" : ""}">
<div class="login-page">
	<aside class="login-hero">
		<div class="login-hero-dots" aria-hidden="true"></div>
		<div class="login-hero-curve" aria-hidden="true"></div>
		<div class="login-hero-skyline" aria-hidden="true"></div>
		<div class="login-hero-content">
			<div class="login-hero-brand">
				<div>
					<strong class="login-hero-brand-name">${t.apply("app_name")}</strong>
					<span class="login-hero-brand-desc">${t.apply("app_desc")}</span>
				</div>
			</div>
			<h2 class="login-hero-headline">${t.apply("login_hero_headline")}</h2>
			<p class="login-hero-desc">${t.apply("login_hero_desc")}</p>
		</div>
	</aside>

	<main class="login-panel">
		<div class="login-card">
			<div class="login-card-toolbar login-anim" style="--d: 0">
				<div class="login-lang-pill">
					<span class="login-lang-globe" aria-hidden="true"></span>
					<a href="?lang=ar" class="${"ar".equals(lang) ? "is-active" : ""}">العربية</a>
					<span class="login-lang-sep" aria-hidden="true">|</span>
					<a href="?lang=en" class="${"en".equals(lang) ? "is-active" : ""}">English</a>
				</div>
			</div>

			<header class="login-card-head login-anim" style="--d: 1">
				<div>
					<h1>${t.apply("login_welcome_back")}</h1>
					<p>${t.apply("login_welcome_sub")}</p>
				</div>
			</header>

			@if(error != null)
				<div class="login-alert login-alert--error login-anim" style="--d: 2" role="alert">${error}</div>
			@endif

			<form method="post" class="login-form login-anim" style="--d: 3">
				<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">

				<div class="login-field">
					<label for="loginPhone">${t.apply("phone")}</label>
					<div class="login-input-wrap">
						<span class="login-input-icon login-input-icon--phone" aria-hidden="true"></span>
						<input type="text" id="loginPhone" name="phone" inputmode="tel"
						       placeholder="${t.apply("phone")}" autocomplete="username" autofocus required>
					</div>
				</div>

				<div class="login-field">
					<label for="loginPassword">${t.apply("password")}</label>
					<div class="login-input-wrap">
						<span class="login-input-icon login-input-icon--lock" aria-hidden="true"></span>
						<input type="password" id="loginPassword" name="password"
						       placeholder="${t.apply("login_password_ph")}" required autocomplete="current-password">
					</div>
				</div>

				<button class="login-submit" type="submit">
					<span class="login-submit-icon" aria-hidden="true"></span>
					<span class="login-submit-text">${t.apply("login_btn")}</span>
				</button>
			</form>
		</div>
	</main>
</div>
</body>
</html>
