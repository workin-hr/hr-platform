@import com.workin.backend.platformadmin.content.PhoneCountry
@import com.workin.backend.platformadmin.web.DashboardSession
@import java.util.List
@import java.util.function.Function
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param List<PhoneCountry> countries
@param boolean actionsEnabled
@param String errorKey = null
@param String currentPage
@param String lang
@param DashboardSession session
@param Function<String, String> t
@param String flash = null
@param String flashType = "success"
@param Function<String, Boolean> available
<%-- dashboard/pages/phone_countries/page.php: a table of the existing rows
     and one add form, both inside the dashboard's own .data-table-card. --%>
@template.admin.layout(
	title = t.apply("nav_phone_countries"),
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
	pageStyles = java.util.List.of("hr-pages.css"),
	body = @`
		@if(errorKey != null)
			<div class="flash flash-error">${t.apply(errorKey)}</div>
		@endif
		@if(!actionsEnabled)
			<div class="flash flash-warning">${t.apply("admin_actions_disabled")}</div>
		@endif

		<div class="data-table-card">
			<div class="data-table-head">
				<h2 class="data-table-title">${t.apply("nav_phone_countries")} (${countries.size()})</h2>
				<div class="data-table-head__actions">
					@if(actionsEnabled)
						<button type="button" class="btn btn-blue btn-sm"
								onclick="crudOpenAdd('pcModal')">+ ${t.apply("add")}</button>
					@endif
				</div>
			</div>
			<div class="table-wrap">
				<table class="tbl">
					<thead>
						<tr>
							<th>#</th>
							<th>${t.apply("country_code")}</th>
							<th>${t.apply("name_ar")}</th>
							<th>${t.apply("name_en")}</th>
							<%-- Its own column in legacy, not a prefix on the code: an
							     emoji flag beside a dial code reads as one token and
							     neither can be scanned down the column. --%>
							<th>${t.apply("flag_emoji")}</th>
							<th>${t.apply("phone_length")}</th>
							<th>${t.apply("phone_prefixes")}</th>
							<th>${t.apply("sort_order")}</th>
							<th>${t.apply("status")}</th>
							@template.admin.actionsHeader(t = t)
						</tr>
					</thead>
					<tbody>
						@for(int index = 0; index < countries.size(); index++)
							!{PhoneCountry country = countries.get(index);}
							<tr>
								<td class="text-muted">${index + 1}</td>
								<td dir="ltr">${country.countryCode()}</td>
								<td>${country.nameAr()}</td>
								<td>${country.nameEn()}</td>
								<td>${country.flagEmoji()}</td>
								<td>${country.phoneLength()}</td>
								<td class="text-muted" dir="ltr">${String.join(", ", country.prefixes())}</td>
								<td>${country.sortOrder()}</td>
								<td>
									@template.admin.statusBadge(status = country.active() ? "active" : "inactive", t = t)
								</td>
								<td class="col-actions">
									<%-- A trigger with nothing behind it opens an empty popover,
									     so the trigger takes the same guard its only item has. --%>
									@if(actionsEnabled)
										@template.admin.rowActions(rowId = country.id(), t = t, items = @`
											<%-- page.php:143 has an edit modal; the controller has
											     had case "edit" all along. Only the way in was
											     missing, so the row could be deleted but not
											     corrected. --%>
											<button type="button" role="menuitem" class="row-actions__item"
													data-dialog="pc-edit"
													data-dialog-id="${country.id()}"
													data-dialog-subject="${country.nameAr()}"
													data-dialog-countryCode="${country.countryCode()}"
													data-dialog-nameAr="${country.nameAr()}"
													data-dialog-nameEn="${country.nameEn()}"
													data-dialog-flagEmoji="${country.flagEmoji()}"
													data-dialog-phoneLength="${country.phoneLength()}"
													data-dialog-prefixes="${String.join(", ", country.prefixes())}"
													data-dialog-sortOrder="${country.sortOrder()}"
													data-dialog-isActive="${country.active() ? "1" : "0"}">${t.apply("edit")}</button>
											<form method="post" class="row-actions__form"
											      data-confirm="${t.apply("confirm_delete")}" data-confirm-tone="danger">
												<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
												<input type="hidden" name="action" value="delete">
												<input type="hidden" name="id" value="${country.id()}">
												<button type="submit" role="menuitem" class="row-actions__item row-actions__item--danger">${t.apply("delete")}</button>
											</form>
										`)
									@else
										<span class="text-muted">—</span>
									@endif
								</td>
							</tr>
						@endfor
						@if(countries.isEmpty())
							<tr><td colspan="10" class="data-table-empty">${t.apply("no_data")}</td></tr>
						@endif
					</tbody>
				</table>
			</div>
		</div>

		@if(actionsEnabled)
			@template.admin.rowDialog(id = "pc-edit", action = "edit",
					title = t.apply("edit_country"), csrfParameterName = csrfParameterName,
					csrfToken = csrfToken, t = t, submitLabel = t.apply("save"),
					fields = @`
						<div class="form-row">
							<label for="pc_edit_code">${t.apply("country_code")}</label>
							<input type="text" id="pc_edit_code" name="countryCode" dir="ltr" required data-dialog-field="countryCode">
						</div>
						<div class="form-row">
							<label for="pc_edit_name_ar">${t.apply("name_ar")}</label>
							<input type="text" id="pc_edit_name_ar" name="nameAr" required data-dialog-field="nameAr">
						</div>
						<div class="form-row">
							<label for="pc_edit_name_en">${t.apply("name_en")}</label>
							<input type="text" id="pc_edit_name_en" name="nameEn" required data-dialog-field="nameEn">
						</div>
						<div class="form-row">
							<label for="pc_edit_flag">${t.apply("flag_emoji")}</label>
							<input type="text" id="pc_edit_flag" name="flagEmoji" data-dialog-field="flagEmoji">
						</div>
						<div class="form-row">
							<label for="pc_edit_length">${t.apply("phone_length")}</label>
							<input type="number" id="pc_edit_length" name="phoneLength" min="4" max="15" required data-dialog-field="phoneLength">
						</div>
						<div class="form-row">
							<label for="pc_edit_prefixes">${t.apply("phone_prefixes")}</label>
							<input type="text" id="pc_edit_prefixes" name="prefixes" dir="ltr" data-dialog-field="prefixes">
						</div>
						<div class="form-row">
							<label for="pc_edit_sort">${t.apply("sort_order")}</label>
							<input type="number" id="pc_edit_sort" name="sortOrder" data-dialog-field="sortOrder">
						</div>
						<%-- Ticked from the row, as legacy's edit modal sets it
						     (phone_countries/page.php:198). Rendered ticked, it
						     re-activated every inactive country saved from here. --%>
						<div class="form-row">
							<label><input type="checkbox" name="isActive" value="1" data-dialog-field="isActive">
								<span>${t.apply("status_active")}</span></label>
						</div>
					`)

			<%-- Legacy opens this form in a modal, behind the "+" button above.
			     Rendered inline it sat open over the table on every visit, whether
			     or not anyone was adding anything. Same form, same fields, same
			     POST; .modal-bg is position:fixed, so it lives at the end of the
			     document and out of the reading order. --%>
			<div class="modal-bg" id="pcModal">
				<div class="modal" role="dialog" aria-modal="true" aria-labelledby="pcModal-title">
					<button type="button" class="modal-close"
							aria-label="${t.apply("cancel")}">&times;</button>
					<h2 id="pcModal-title">${t.apply("add_country")}</h2>
					<form method="post">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="add">
					<%-- pcAddModal (phone_countries/page.php:95-138): one field to a row, in its
					     order, with its default length and its footer. --%>
					<div class="form-row">
						<label for="pc_add_code">${t.apply("country_code")}</label>
						<input type="text" id="pc_add_code" name="countryCode" dir="ltr" placeholder="+20" required>
					</div>
					<div class="form-row">
						<label for="pc_add_name_ar">${t.apply("name_ar")}</label>
						<input type="text" id="pc_add_name_ar" name="nameAr" required>
					</div>
					<div class="form-row">
						<label for="pc_add_name_en">${t.apply("name_en")}</label>
						<input type="text" id="pc_add_name_en" name="nameEn" required>
					</div>
					<div class="form-row">
						<label for="pc_add_flag">${t.apply("flag_emoji")}</label>
						<input type="text" id="pc_add_flag" name="flagEmoji" placeholder="🇪🇬">
					</div>
					<div class="form-row">
						<label for="pc_add_length">${t.apply("phone_length")}</label>
						<input type="number" id="pc_add_length" name="phoneLength" min="4" max="15" value="11" required>
					</div>
					<div class="form-row">
						<%-- Free text, comma or space separated, as the dashboard's field is. --%>
						<label for="pc_add_prefixes">${t.apply("phone_prefixes")}</label>
						<input type="text" id="pc_add_prefixes" name="prefixes" dir="ltr" placeholder="10, 11, 12, 15">
					</div>
					<div class="form-row">
						<label for="pc_add_sort">${t.apply("sort_order")}</label>
						<input type="number" id="pc_add_sort" name="sortOrder" value="0">
					</div>
					<div class="form-row">
						<label><input type="checkbox" name="isActive" value="1" checked>
							<span>${t.apply("status_active")}</span></label>
					</div>
					<div class="form-footer">
						<button type="submit" class="btn btn-blue">${t.apply("save")}</button>
					</div>
				</form>
				</div>
			</div>
		@endif
	`)
