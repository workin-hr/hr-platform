@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.content.Banner
@import com.workin.backend.platformadmin.content.Faq
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param List<Banner> banners
<%-- The row being edited, from ?edit=<id>. Null when adding. --%>
@param Banner editBanner = null
@param List<String> routes
@param boolean actionsEnabled
@param String errorKey = null
@param String currentPage
@param String lang
@param com.workin.backend.platformadmin.web.DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
!{boolean arabic = "ar".equals(lang);}
!{boolean canWrite = actionsEnabled;}
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
		@endif

		<div class="data-table-card">
			<div class="data-table-head">
				<h2 class="data-table-title">${t.apply("nav_banners")} (${banners.size()})</h2>
				<div class="data-table-head__actions">
					@if(canWrite)
						<button type="button" class="btn btn-blue btn-sm"
								onclick="crudOpenAdd('bannerModal')">+ ${t.apply("add")}</button>
					@endif
				</div>
			</div>
			<div class="table-wrap">
				<table class="tbl">
					<thead>
						<tr>
							<th>#</th>
							<th>${t.apply("banner_preview")}</th>
							<%-- Both titles: this page is where somebody checks that the
							     Arabic and the English banner say the same thing, and one
							     column chosen by the viewer's language cannot show that. --%>
							<th>${t.apply("title_ar")}</th>
							<th>${t.apply("title_en")}</th>
							<th>${t.apply("app_platform")}</th>
							<th>${t.apply("button_action_type")}</th>
							<th>${t.apply("sort_order")}</th>
							<th>${t.apply("status")}</th>
							<th>${t.apply("reg_date")}</th>
							<th class="col-actions"><span class="sr-only">${t.apply("actions")}</span></th>
						</tr>
					</thead>
					<tbody>
						@for(int index = 0; index < banners.size(); index++)
							!{Banner banner = banners.get(index);}
							<tr>
								<td class="text-muted">${index + 1}</td>
								<%-- A banner whose file is gone: the placeholder, not the
								     browser's broken-image icon (img-fallback.js). --%>
								<td><img src="${banner.imageUrl()}" alt="" class="tbl-photo" width="64" height="36"
								         data-fallback-initials="—" data-fallback-class="tbl-photo-missing"></td>
								<td>${banner.titleAr() == null || banner.titleAr().isBlank() ? "—" : banner.titleAr()}</td>
								<td class="text-muted">${banner.titleEn() == null || banner.titleEn().isBlank()
									? "—" : banner.titleEn()}</td>
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
								<td class="text-muted">${banner.createdDate()}</td>
								<td class="col-actions">
									<%-- A trigger with nothing behind it opens an empty popover,
									     so the trigger takes the same guard its only item has. --%>
									@if(canWrite)
										@template.admin.rowActions(rowId = banner.id(), t = t, items = @`
											<%-- D-210: the way in to the edit action. A link rather
											     than a dialog trigger, because the modal is prefilled
											     by the server -- the image field cannot be filled from
											     a data- attribute, and the form is multipart. --%>
											<a href="/admin/banners?edit=${banner.id()}" role="menuitem"
											   class="row-actions__item">${t.apply("edit")}</a>
											<form method="post" class="row-actions__form"
											      onsubmit="return confirm('${t.apply("confirm_delete")}')">
												<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
												<input type="hidden" name="action" value="delete">
												<input type="hidden" name="id" value="${banner.id()}">
												<button type="submit" role="menuitem" class="row-actions__item row-actions__item--danger">${t.apply("delete")}</button>
											</form>
										`)
									@else
										<span class="text-muted">—</span>
									@endif
								</td>
							</tr>
						@endfor
						@if(banners.isEmpty())
							<tr><td colspan="10" class="data-table-empty">${t.apply("no_data")}</td></tr>
						@endif
					</tbody>
				</table>
			</div>
		</div>

		@if(canWrite)
			<%-- Legacy opens this form in a modal, behind the "+" button above.
			     Rendered inline it sat open over the table on every visit, whether
			     or not anyone was adding anything. Same form, same fields, same
			     POST; .modal-bg is position:fixed, so it lives at the end of the
			     document and out of the reading order. --%>
			<%-- One modal for both, as legacy has one: open on arrival when a row
			     is being edited, opened by the "+" button otherwise. The image is
			     required to add and optional to edit -- update() keeps the stored
			     one when no file is posted, so demanding it again would make
			     correcting a title mean re-uploading the picture. --%>
			<div class="modal-bg${editBanner == null ? "" : " open"}" id="bannerModal">
				<div class="modal" role="dialog" aria-modal="true" aria-labelledby="bannerModal-title">
					<button type="button" class="modal-close"
							aria-label="${t.apply("cancel")}">&times;</button>
					<h2 id="bannerModal-title">${t.apply(editBanner == null ? "add" : "edit_banner")}</h2>
					<form method="post" enctype="multipart/form-data">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="${editBanner == null ? "add" : "edit"}">
					@if(editBanner != null)
						<input type="hidden" name="id" value="${editBanner.id()}">
					@endif
					<div class="form-row">
						<label>${t.apply("banner_image")}
							<input type="file" name="image" accept="image/jpeg,image/png,image/webp"
							       required="${editBanner == null}">
							<small>${t.apply("banner_upload_hint")}</small></label>
						<label>${t.apply("app_platform")}
							<select name="platform">
								@for(Faq.Platform p : Faq.Platform.values())
									<option value="${p.stored()}"
											selected="${editBanner != null && editBanner.platform() == p}">${p.stored()}</option>
								@endfor
							</select></label>
						<label>${t.apply("sort_order")}<input type="number" name="sortOrder"
								value="${editBanner == null ? "0" : String.valueOf(editBanner.sortOrder())}"></label>
					</div>
					<div class="form-row">
						<label>${t.apply("title_ar")}<input type="text" name="titleAr"
								value="${editBanner == null ? "" : editBanner.titleAr()}"></label>
						<label>${t.apply("title_en")}<input type="text" name="titleEn"
								value="${editBanner == null ? "" : editBanner.titleEn()}"></label>
					</div>
					<div class="form-row">
						<label>${t.apply("description_ar")}<textarea name="descriptionAr" rows="2">${editBanner == null ? "" : editBanner.descriptionAr()}</textarea></label>
						<label>${t.apply("description_en")}<textarea name="descriptionEn" rows="2">${editBanner == null ? "" : editBanner.descriptionEn()}</textarea></label>
					</div>
					<div class="form-row">
						<label>${t.apply("button_label_ar")}<input type="text" name="buttonLabelAr"
								value="${editBanner == null ? "" : editBanner.buttonLabelAr()}"></label>
						<label>${t.apply("button_label_en")}<input type="text" name="buttonLabelEn"
								value="${editBanner == null ? "" : editBanner.buttonLabelEn()}"></label>
					</div>
					<div class="form-row">
						<label>${t.apply("button_action_type")}
							<select name="actionType">
								@for(Banner.Action a : Banner.Action.values())
									<option value="${a.stored()}"
											selected="${editBanner != null && editBanner.buttonActionType() == a}">${a.stored()}</option>
								@endfor
							</select></label>
						<%-- One field for the external URL and one datalist-backed field for
						     the internal route. The route list is the allowlist itself, so
						     the form offers only values the server will accept. --%>
						<label>${t.apply("button_action_value")}
							<input type="text" name="actionValue" list="bannerRoutes"
							       value="${editBanner == null || editBanner.buttonActionValue() == null
								       ? "" : editBanner.buttonActionValue()}"
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
						<label>
							<input type="checkbox" name="isActive" value="1"
						       checked="${editBanner == null || editBanner.active()}">
							<span>${t.apply("status_active")}</span></label>
					</div>
					<%-- The save sits in a form-footer so it stays put while the
					     fields scroll: .modal is the scroll container, and a form
					     longer than the box otherwise pushes its own button out of
					     reach. Legacy leaves it loose here and carries the same
					     problem; the class is legacy's own. --%>
					<div class="form-footer">
						<button type="submit" class="btn btn-blue">${t.apply("save")}</button>
					</div>
				</form>
				</div>
			</div>
		@endif
	`)
