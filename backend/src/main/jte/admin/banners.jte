@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.content.Banner
@import com.workin.backend.platformadmin.content.Faq
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param List<Banner> banners
@param List<String> routes
@param boolean actionsEnabled
@param boolean factorBound
@param String errorKey = null
@param String currentPage
@param String lang
@param com.workin.backend.platformadmin.web.DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
!{boolean arabic = "ar".equals(lang);}
!{boolean canWrite = actionsEnabled && factorBound;}
<%-- dashboard/pages/banners/page.php. The add form is the only multipart
     form on this surface; the file goes to LegacyFileUploads, which names
     the stored file from its sniffed type (D-154). --%>
@template.admin.layout(
	title = t.apply("nav_banners"),
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentAdminPhone = currentAdminPhone,
	currentPage = currentPage,
	lang = lang,
	session = session,
	t = t,
	available = available,
	pageStyles = java.util.List.of("hr-pages.css", "banners.css"),
	body = @`
		@if(errorKey != null)
			<div class="flash flash-error">${t.apply(errorKey)}</div>
		@endif
		@if(!actionsEnabled)
			<div class="flash flash-warning">${t.apply("admin_actions_disabled")}</div>
		@elseif(!factorBound)
			<div class="flash flash-warning">${t.apply("mfa_required_for_actions")}</div>
		@endif

		<div class="data-table-card">
			<div class="data-table-head">
				<h2 class="data-table-title">${t.apply("nav_banners")} (${banners.size()})</h2>
			</div>
			<div class="table-wrap">
				<table class="tbl">
					<thead>
						<tr>
							<th>${t.apply("banner_image")}</th>
							<th>${t.apply("title_ar")}</th>
							<th>${t.apply("app_platform")}</th>
							<th>${t.apply("button_action_type")}</th>
							<th>${t.apply("sort_order")}</th>
							<th>${t.apply("status")}</th>
							<th class="col-actions"><span class="sr-only">${t.apply("actions")}</span></th>
						</tr>
					</thead>
					<tbody>
						@for(Banner banner : banners)
							<tr>
								<td><img src="${banner.imageUrl()}" alt="" class="tbl-photo" width="64" height="36"></td>
								<td>${arabic ? banner.titleAr() : banner.titleEn()}</td>
								<td><span class="badge badge-blue">${banner.platform().stored()}</span></td>
								<td>
									${banner.buttonActionType().stored()}
									@if(banner.buttonActionValue() != null)
										<span class="badge badge-gray">${banner.buttonActionValue()}</span>
									@endif
								</td>
								<td>${banner.sortOrder()}</td>
								<td><span class="badge ${banner.active() ? "badge-green" : "badge-gray"}">
									${t.apply(banner.active() ? "status_active" : "status_inactive")}</span></td>
								<td class="col-actions">
									@if(canWrite)
										<form method="post" class="row-actions__form"
										      onsubmit="return confirm('${t.apply("confirm_delete")}')">
											<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
											<input type="hidden" name="action" value="delete">
											<input type="hidden" name="id" value="${banner.id()}">
											<button type="submit" class="btn btn-sm btn-red">${t.apply("delete")}</button>
										</form>
									@endif
								</td>
							</tr>
						@endfor
						@if(banners.isEmpty())
							<tr><td colspan="7" class="data-table-empty">${t.apply("no_data")}</td></tr>
						@endif
					</tbody>
				</table>
			</div>
		</div>

		@if(canWrite)
			<div class="data-table-card">
				<div class="data-table-head">
					<h2 class="data-table-title">${t.apply("add")}</h2>
				</div>
				<form method="post" enctype="multipart/form-data">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="add">
					<div class="form-row">
						<label>${t.apply("banner_image")}
							<input type="file" name="image" accept="image/jpeg,image/png,image/webp" required>
							<small>${t.apply("banner_upload_hint")}</small></label>
						<label>${t.apply("app_platform")}
							<select name="platform">
								@for(Faq.Platform p : Faq.Platform.values())
									<option value="${p.stored()}">${p.stored()}</option>
								@endfor
							</select></label>
						<label>${t.apply("sort_order")}<input type="number" name="sortOrder" value="0"></label>
					</div>
					<div class="form-row">
						<label>${t.apply("title_ar")}<input type="text" name="titleAr"></label>
						<label>${t.apply("title_en")}<input type="text" name="titleEn"></label>
					</div>
					<div class="form-row">
						<label>${t.apply("description_ar")}<textarea name="descriptionAr" rows="2"></textarea></label>
						<label>${t.apply("description_en")}<textarea name="descriptionEn" rows="2"></textarea></label>
					</div>
					<div class="form-row">
						<label>${t.apply("button_label_ar")}<input type="text" name="buttonLabelAr"></label>
						<label>${t.apply("button_label_en")}<input type="text" name="buttonLabelEn"></label>
					</div>
					<div class="form-row">
						<label>${t.apply("button_action_type")}
							<select name="actionType">
								@for(Banner.Action a : Banner.Action.values())
									<option value="${a.stored()}">${a.stored()}</option>
								@endfor
							</select></label>
						<%-- One field for the external URL and one datalist-backed field for
						     the internal route. The route list is the allowlist itself, so
						     the form offers only values the server will accept. --%>
						<label>${t.apply("button_action_value")}
							<input type="text" name="actionValue" list="bannerRoutes"
							       placeholder="${t.apply("banner_action_url_placeholder")}"></label>
						<datalist id="bannerRoutes">
							@for(String route : routes)
								<option value="${route}"></option>
							@endfor
						</datalist>
					</div>
					<div class="form-row">
						<label>${t.apply("country_code")}
							<input type="text" name="whatsappCountryCode" placeholder="+20"></label>
						<label>${t.apply("banner_whatsapp_number")}<input type="text" name="whatsappPhone"></label>
						<label class="login-remember">
							<input type="checkbox" name="isActive" value="1" checked>
							<span>${t.apply("status_active")}</span></label>
					</div>
					<button type="submit" class="btn btn-blue">${t.apply("save")}</button>
				</form>
			</div>
		@endif
	`)
