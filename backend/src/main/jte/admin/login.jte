@import java.util.function.Function
@param String csrfParameterName
@param String csrfToken
@param String errorKey = null
@param String reason = null
@param String lang
@param Function<String, String> t
<%-- dashboard/pages/login/page.php, as production serves it: the admin tab
     only (DASHBOARD_LOGIN_SHOW_TYPE_TABS is false there), so one password
     field and no phone. Standalone rather than through admin/layout, because
     the login has its own full-page design -- the hero beside the card -- and
     its stylesheet and script are the ones copied to /admin/_assets verbatim.
     What differs is behind the form (ADR-0018): the password is a bcrypt hash,
     the miss budget is per client, the session rotates on success and is
     Secure, HttpOnly and CSRF-bound. --%>
!{boolean arabic = "ar".equals(lang);}
!{boolean hasAlert = errorKey != null || "timeout".equals(reason);}
<!DOCTYPE html>
<html lang="${lang}" dir="${arabic ? "rtl" : "ltr"}">
<head>
	<meta charset="UTF-8">
	<meta name="viewport" content="width=device-width, initial-scale=1">
	<title>${t.apply("login_title")} — ${t.apply("app_name")}</title>
	<link rel="stylesheet" href="/admin/_assets/login.css">
</head>
<body class="login-body${hasAlert ? " login-has-alert" : ""}${errorKey != null ? " login-shake" : ""}">
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
			<div class="login-hero-grid">
				@for(String feature : java.util.List.of("hr", "sec", "rep", "br", "dec", "perf"))
					<article class="login-hero-feature">
						<span class="login-hero-feature-icon login-hero-feature-icon--${
							switch (feature) {
								case "hr" -> "users"; case "sec" -> "shield"; case "rep" -> "chart";
								case "br" -> "building"; case "dec" -> "target"; default -> "bolt";
							}}" aria-hidden="true"></span>
						<div>
							<h3>${t.apply("login_feat_" + feature + "_title")}</h3>
							<p>${t.apply("login_feat_" + feature + "_desc")}</p>
						</div>
					</article>
				@endfor
			</div>
		</div>
	</aside>

	<main class="login-panel">
		<div class="login-card">
			<div class="login-card-toolbar login-anim" style="--d: 0">
				<div class="login-lang-pill">
					<span class="login-lang-globe" aria-hidden="true"></span>
					<a href="?lang=ar" class="${arabic ? "is-active" : ""}">العربية</a>
					<span class="login-lang-sep" aria-hidden="true">|</span>
					<a href="?lang=en" class="${arabic ? "" : "is-active"}">English</a>
				</div>
			</div>

			<header class="login-card-head login-anim" style="--d: 1">
				<div>
					<h1>${t.apply("login_welcome_back")}</h1>
					<p>${t.apply("login_welcome_sub")}</p>
				</div>
			</header>

			@if("timeout".equals(reason))
				<div class="login-alert login-alert--warn login-anim" style="--d: 2" role="alert">
					${arabic ? "انتهت مدة الجلسة — الرجاء الدخول مجدداً" : "Session expired — please sign in again"}
				</div>
			@endif
			@if(errorKey != null)
				<div class="login-alert login-alert--error login-anim" style="--d: 2" role="alert">${t.apply(errorKey)}</div>
			@endif

			<div id="loginValidation" class="login-alert login-alert--error login-validation" hidden role="alert"></div>
			<form method="POST" id="loginForm" class="login-form login-anim" style="--d: 3" novalidate
			      data-msg-required="${t.apply("error_required")}"
			      data-msg-fill="${t.apply("login_fill_required")}"
			      data-msg-invalid-phone="${t.apply("error_invalid_phone")}"
			      data-admin-only="1">
				<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
				<input type="hidden" name="user_type" id="userType" value="admin">
				<div class="login-field">
					<label for="loginPassword">${t.apply("password")}</label>
					<div class="login-input-wrap">
						<span class="login-input-icon login-input-icon--lock" aria-hidden="true"></span>
						<input type="password" id="loginPassword" name="password"
						       placeholder="${t.apply("login_password_ph")}" autofocus required
						       autocomplete="current-password">
						<button type="button" class="login-password-toggle" id="passwordToggle"
						        aria-label="${arabic ? "إظهار كلمة المرور" : "Show password"}" aria-pressed="false"></button>
					</div>
				</div>
				<div class="login-form-row">
					<label class="login-remember">
						<input type="checkbox" name="remember" value="1">
						<span>${t.apply("login_remember")}</span>
					</label>
					<a href="#" class="login-forgot" onclick="return false">${t.apply("login_forgot")}</a>
				</div>
				<button class="login-submit" type="submit">
					<span class="login-submit-icon" aria-hidden="true"></span>
					<span class="login-submit-text">${t.apply("login_btn")}</span>
				</button>
			</form>
		</div>
	</main>
</div>
<script src="/admin/_assets/phone-validator.js"></script>
<script src="/admin/_assets/login.js"></script>
</body>
</html>
