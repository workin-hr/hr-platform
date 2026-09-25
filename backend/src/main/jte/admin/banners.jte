@import com.workin.backend.platformadmin.content.Banner
@import com.workin.backend.platformadmin.content.BannerForm
@import com.workin.backend.platformadmin.content.Faq
@import com.workin.backend.platformadmin.web.DashboardSession
@import java.util.List
@import java.util.function.Function
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param List<Banner> banners
<%-- The row being edited, from ?edit=<id>. Null when adding. --%>
@param Banner editBanner = null
<%-- The edited WhatsApp button's stored number, split into its two inputs (D-230). --%>
@param BannerForm.WhatsappParts whatsappParts = null
@param List<String> routes
@param boolean actionsEnabled
@param String errorKey = null
@param String currentPage
@param String lang
@param DashboardSession session
@param Function<String, String> t
@param String flash = null
@param String flashType = "success"
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
	flash = flash,
	flashType = flashType,
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
							@template.admin.actionsHeader(t = t)
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
								<td><span class="badge badge-blue">${t.apply(banner.platform().labelKey())}</span></td>
								<td>
									${t.apply(banner.buttonActionType().labelKey())}
									@if(banner.buttonActionValue() != null)
										<span class="badge badge-gray">${banner.buttonActionValue()}</span>
									@endif
								</td>
								<td>${banner.sortOrder()}</td>
								<td>@template.admin.statusBadge(status = banner.active() ? "active" : "inactive", t = t)</td>
								<td class="nowrap text-muted">${banner.createdDate()}</td>
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
											      data-confirm="${t.apply("confirm_delete")}" data-confirm-tone="danger">
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
					<h2 id="bannerModal-title">${t.apply(editBanner == null ? "add_banner" : "edit_banner")}</h2>
					<form method="post" enctype="multipart/form-data">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="${editBanner == null ? "add" : "edit"}">
					@if(editBanner != null)
						<input type="hidden" name="id" value="${editBanner.id()}">
					@endif
					<%-- bannerModal (banners/page.php:111-232): its rows and its pairs in
					     .banner-form-grid. Legacy writes the WhatsApp code as a select beside
					     the number; here it is still a text field in its own row. Legacy's
					     script shows the action value, the WhatsApp fields and the button
					     labels only for the action type that uses them; here they stay
					     visible, and the server ignores the ones the type does not use. --%>
					<div class="form-row banner-form-row--image">
						<label for="banner_image_file">${t.apply("banner_image")}</label>
						<input type="file" id="banner_image_file" name="image" accept="image/jpeg,image/png,image/webp"
						       required="${editBanner == null}">
						<span class="form-hint">${t.apply("banner_upload_hint")}</span>
					</div>
					<div class="banner-form-grid">
						<div class="form-row">
							<label for="banner_sort">${t.apply("sort_order")}</label>
							<input type="number" id="banner_sort" name="sortOrder"
							       value="${editBanner == null ? "0" : String.valueOf(editBanner.sortOrder())}">
						</div>
						<div class="form-row">
							<label for="banner_platform">${t.apply("app_platform")}</label>
							<select id="banner_platform" name="platform">
								@for(Faq.Platform p : Faq.Platform.offered())
									<option value="${p.stored()}"
									        selected="${editBanner != null && editBanner.platform() == p}">${t.apply(p.labelKey())}</option>
								@endfor
							</select>
						</div>
					</div>
					<div class="form-row">
						<label><input type="checkbox" name="isActive" value="1"
						              checked="${editBanner == null || editBanner.active()}">
							<span>${t.apply("status_active")}</span></label>
					</div>
					<div class="banner-form-grid">
						<div class="form-row">
							<label for="banner_title_ar">${t.apply("title_ar")}</label>
							<input type="text" id="banner_title_ar" name="titleAr"
							       value="${editBanner == null ? "" : editBanner.titleAr()}">
						</div>
						<div class="form-row">
							<label for="banner_title_en">${t.apply("title_en")}</label>
							<input type="text" id="banner_title_en" name="titleEn"
							       value="${editBanner == null ? "" : editBanner.titleEn()}">
						</div>
					</div>
					<div class="banner-form-grid">
						<div class="form-row">
							<label for="banner_desc_ar">${t.apply("description_ar")}</label>
							<textarea id="banner_desc_ar" name="descriptionAr" rows="3">${editBanner == null ? "" : editBanner.descriptionAr()}</textarea>
						</div>
						<div class="form-row">
							<label for="banner_desc_en">${t.apply("description_en")}</label>
							<textarea id="banner_desc_en" name="descriptionEn" rows="3">${editBanner == null ? "" : editBanner.descriptionEn()}</textarea>
						</div>
					</div>
					<div class="form-row">
						<label for="banner_action_type">${t.apply("button_action_type")}</label>
						<select id="banner_action_type" name="actionType">
							@for(Banner.Action a : Banner.Action.offered())
								<option value="${a.stored()}"
								        selected="${editBanner != null && editBanner.buttonActionType() == a}">${t.apply(a.labelKey())}</option>
							@endfor
						</select>
					</div>
					<div class="form-row">
						<%-- One field for the external URL and one datalist-backed field for
						     the internal route. The route list is the allowlist itself, so
						     the form offers only values the server will accept. Empty for a
						     WhatsApp button, as legacy's edit leaves it: that number lives in
						     the two fields below, not here. --%>
						<label for="banner_action_value">${t.apply("button_action_value")}</label>
						<input type="text" id="banner_action_value" name="actionValue" list="bannerRoutes"
						       value="${editBanner == null || editBanner.buttonActionValue() == null
							       || whatsappParts != null ? "" : editBanner.buttonActionValue()}"
						       placeholder="${t.apply("banner_action_url_placeholder")}">
						<datalist id="bannerRoutes">
							@for(String route : routes)
								<option value="${route}"></option>
							@endfor
						</datalist>
					</div>
					<div class="form-row">
						<%-- A stored WhatsApp number split back into the parts it was built
						     from (D-230). Left empty, an unchanged save would store no number. --%>
						<label for="banner_whatsapp_country">${t.apply("country_code")}</label>
						<input type="text" id="banner_whatsapp_country" name="whatsappCountryCode" dir="ltr" placeholder="+20"
						       value="${whatsappParts == null ? "" : whatsappParts.countryCode()}">
					</div>
					<div class="form-row">
						<label for="banner_whatsapp_phone">${t.apply("banner_whatsapp_number")}</label>
						<input type="text" id="banner_whatsapp_phone" name="whatsappPhone" inputmode="tel"
						       placeholder="${t.apply("banner_action_whatsapp_placeholder")}"
						       value="${whatsappParts == null ? "" : whatsappParts.local()}">
					</div>
					<div class="banner-form-grid">
						<div class="form-row">
							<label for="banner_btn_ar">${t.apply("button_label_ar")}</label>
							<input type="text" id="banner_btn_ar" name="buttonLabelAr"
							       value="${editBanner == null ? "" : editBanner.buttonLabelAr()}">
						</div>
						<div class="form-row">
							<label for="banner_btn_en">${t.apply("button_label_en")}</label>
							<input type="text" id="banner_btn_en" name="buttonLabelEn"
							       value="${editBanner == null ? "" : editBanner.buttonLabelEn()}">
						</div>
					</div>
					<%-- The save sits in a form-footer, as legacy's does, so it stays put
					     while the fields scroll: .modal is the scroll container. --%>
					<div class="form-footer">
						<button type="submit" class="btn btn-blue">${t.apply("save")}</button>
					</div>
				</form>
				</div>
			</div>
		@endif
	`)
