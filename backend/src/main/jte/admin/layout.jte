@import gg.jte.Content
@param String title
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone = null
@param Content body
<!DOCTYPE html>
<html lang="en">
<head>
	<meta charset="utf-8">
	<meta name="viewport" content="width=device-width, initial-scale=1">
	<title>${title} &middot; Workin platform admin</title>
	<%-- Styles are inline on purpose. A /admin/assets/** route would have to be
	     permitAll on a chain whose whole job is that nothing under /admin is
	     reachable unauthenticated, and ADR-0015 prerequisite 5 is specifically
	     about routes that quietly land on the wrong chain. One less path. --%>
	<style>
		:root { color-scheme: light dark; --bg:#f6f7f9; --fg:#16202c; --muted:#5b6876;
			--line:#dfe4ea; --card:#fff; --accent:#1f6feb; --danger:#b3261e; }
		@media (prefers-color-scheme: dark) {
			:root { --bg:#12161b; --fg:#e6edf3; --muted:#9aa7b4; --line:#28313b;
				--card:#1a2027; --accent:#4c8dff; --danger:#ef5350; }
		}
		* { box-sizing: border-box; }
		body { margin:0; background:var(--bg); color:var(--fg);
			font:15px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif; }
		.topbar { display:flex; align-items:center; justify-content:space-between;
			padding:.75rem 1.25rem; background:var(--card); border-bottom:1px solid var(--line); }
		.brand { font-weight:650; letter-spacing:-.01em; }
		.brand-sub { font-weight:400; color:var(--muted); }
		.topbar-session { display:flex; align-items:center; gap:.75rem; margin:0; }
		.who { color:var(--muted); font-size:.875rem; }
		.linkish { background:none; border:0; padding:0; color:var(--accent);
			font:inherit; cursor:pointer; text-decoration:underline; }
		.page { max-width:60rem; margin:0 auto; padding:1.5rem 1.25rem 3rem; }
		.card { background:var(--card); border:1px solid var(--line); border-radius:10px;
			padding:1.25rem; }
		.card + .card { margin-top:1rem; }
		h1 { font-size:1.35rem; margin:0 0 .25rem; letter-spacing:-.01em; }
		.sub { color:var(--muted); margin:0 0 1.25rem; }
		label { display:block; font-weight:550; margin:0 0 .3rem; font-size:.9rem; }
		input[type=text],input[type=password] { width:100%; padding:.55rem .7rem;
			border:1px solid var(--line); border-radius:7px; background:var(--bg);
			color:var(--fg); font:inherit; }
		.field + .field { margin-top:.9rem; }
		button.primary { margin-top:1.15rem; width:100%; padding:.6rem 1rem; border:0;
			border-radius:7px; background:var(--accent); color:#fff; font:inherit;
			font-weight:600; cursor:pointer; }
		.error { margin:0 0 1rem; padding:.6rem .75rem; border-radius:7px;
			background:color-mix(in srgb, var(--danger) 12%, transparent);
			color:var(--danger); font-size:.9rem; }
		.login-wrap { max-width:23rem; margin:3rem auto; }
		dl.facts { display:grid; grid-template-columns:auto 1fr; gap:.4rem 1rem; margin:0; }
		dl.facts dt { color:var(--muted); }
		dl.facts dd { margin:0; }
		.notice { color:var(--muted); font-size:.875rem; margin-top:1rem; }
	</style>
</head>
<body>
	<header class="topbar">
		<span class="brand">Workin <span class="brand-sub">platform admin</span></span>
		@if(currentAdminPhone != null)
			<form method="post" action="/admin/logout" class="topbar-session">
				<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
				<span class="who">${currentAdminPhone}</span>
				<button type="submit" class="linkish">Sign out</button>
			</form>
		@endif
	</header>
	<main class="page">
		${body}
	</main>
</body>
</html>
