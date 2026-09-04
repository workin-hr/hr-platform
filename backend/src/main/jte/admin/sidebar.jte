@import com.workin.backend.platformadmin.web.AdminIcons
@import com.workin.backend.platformadmin.web.AdminNav
@import java.util.function.Function
@param String currentPage
@param String lang
@param boolean isAdmin
@param String csrfParameterName
@param String csrfToken
@param Function<String, String> t
@param Function<String, Boolean> available
<%-- Structure and class names are the dashboard's own (sidebar/view.php and
     render.php); the copied sidebar.css lays this out and nothing else will. --%>
<aside class="sidebar">
	<div class="logo">
		<span class="logo-text">${t.apply("app_name")}</span>
	</div>
	<nav>
		<a href="${AdminNav.HOME.href()}"
		   class="nav-link${AdminNav.isCurrent(AdminNav.HOME, currentPage) ? " active" : ""}">
			<span class="nav-item-leading">$unsafe{AdminIcons.of(AdminNav.HOME.icon())}<span
					class="nav-label">${t.apply(AdminNav.HOME.labelKey())}</span></span>
		</a>
		@for(AdminNav.Group group : AdminNav.GROUPS)
			<details class="nav-group" data-nav-group="${group.icon()}"
			         open="${AdminNav.isOpen(group, currentPage)}">
				<summary class="nav-group-toggle${AdminNav.isOpen(group, currentPage) ? " active" : ""}">
					<span class="nav-item-leading">$unsafe{AdminIcons.of(group.icon())}<span
							class="nav-label">${t.apply(group.labelKey())}</span></span>
					<span class="nav-chevron" aria-hidden="true">$unsafe{AdminIcons.chevron()}</span>
				</summary>
				<div class="nav-group-items-wrap">
					<div class="nav-group-items">
						@for(AdminNav.Item item : group.items())
							@if(!item.adminOnly() || isAdmin)
								@if(available.apply(item.page()))
									<a href="${item.href()}"
									   class="nav-link nav-sublink${AdminNav.isCurrent(item, currentPage) ? " active" : ""}">
										<span class="nav-item-leading">$unsafe{AdminIcons.of(item.icon())}<span
												class="nav-label">${t.apply(item.labelKey())}</span></span>
									</a>
								@else
									<%-- Not yet ported. Shown, not hidden: a sidebar that omits half
									     the product looks finished when it is not. --%>
									<span class="nav-link nav-sublink is-unported" aria-disabled="true"
									      title="${t.apply("loading")}">
										<span class="nav-item-leading">$unsafe{AdminIcons.of(item.icon())}<span
												class="nav-label">${t.apply(item.labelKey())}</span></span>
									</span>
								@endif
							@endif
						@endfor
					</div>
				</div>
			</details>
		@endfor
	</nav>
	<footer class="sidebar-footer">
		<div class="sidebar-lang" role="group" aria-label="${t.apply("language")}">
			<a href="?lang=ar" class="sidebar-lang__btn${"ar".equals(lang) ? " is-active" : ""}">عربي</a>
			<a href="?lang=en" class="sidebar-lang__btn${"en".equals(lang) ? " is-active" : ""}">English</a>
		</div>
		<form method="post" action="/admin/logout" class="sidebar-logout-form">
			<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
			<button type="submit" class="sidebar-logout">
				$unsafe{AdminIcons.of("logout")}<span>${t.apply("logout")}</span>
			</button>
		</form>
	</footer>
</aside>
