@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.content.PhoneCountry
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param List<PhoneCountry> countries
@param boolean actionsEnabled
@param boolean factorBound
@param String errorKey = null
@param String currentPage
@param String lang
@param com.workin.backend.platformadmin.web.DashboardSession session
@param Function<String, String> t
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
	available = available,
	pageStyles = java.util.List.of("hr-pages.css"),
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
				<h2 class="data-table-title">${t.apply("nav_phone_countries")} (${countries.size()})</h2>
				<div class="data-table-head__actions">
					@if(actionsEnabled && factorBound)
						<button type="button" class="btn btn-blue btn-sm"
								onclick="crudOpenAdd('pcModal')">+ ${t.apply("add")}</button>
					@endif
				</div>
			</div>
			<div class="table-wrap">
				<table class="tbl">
					<thead>
						<tr>
							<th>${t.apply("country_code")}</th>
							<th>${t.apply("name_ar")}</th>
							<th>${t.apply("name_en")}</th>
							<th>${t.apply("phone_length")}</th>
							<th>${t.apply("phone_prefixes")}</th>
							<th>${t.apply("status")}</th>
							<th class="col-actions"><span class="sr-only">${t.apply("actions")}</span></th>
						</tr>
					</thead>
					<tbody>
						@for(PhoneCountry country : countries)
							<tr>
								<td>${country.flagEmoji()} ${country.countryCode()}</td>
								<td>${country.nameAr()}</td>
								<td>${country.nameEn()}</td>
								<td>${country.phoneLength()}</td>
								<td>${String.join(", ", country.prefixes())}</td>
								<td>
									<span class="badge ${country.active() ? "badge-green" : "badge-gray"}">
										${t.apply(country.active() ? "status_active" : "status_inactive")}
									</span>
								</td>
								<td class="col-actions">
									@template.admin.rowActions(rowId = country.id(), t = t, items = @`
										@if(actionsEnabled && factorBound)
											<form method="post" class="row-actions__form"
											      onsubmit="return confirm('${t.apply("confirm_delete")}')">
												<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
												<input type="hidden" name="action" value="delete">
												<input type="hidden" name="id" value="${country.id()}">
												<button type="submit" role="menuitem" class="row-actions__item row-actions__item--danger">${t.apply("delete")}</button>
											</form>
										@endif
									`)
								</td>
							</tr>
						@endfor
						@if(countries.isEmpty())
							<tr><td colspan="7" class="data-table-empty">${t.apply("no_data")}</td></tr>
						@endif
					</tbody>
				</table>
			</div>
		</div>

		@if(actionsEnabled && factorBound)
			<%-- Legacy opens this form in a modal, behind the "+" button above.
			     Rendered inline it sat open over the table on every visit, whether
			     or not anyone was adding anything. Same form, same fields, same
			     POST; .modal-bg is position:fixed, so it lives at the end of the
			     document and out of the reading order. --%>
			<div class="modal-bg" id="pcModal">
				<div class="modal" role="dialog" aria-modal="true" aria-labelledby="pcModal-title">
					<button type="button" class="modal-close"
							aria-label="${t.apply("cancel")}">&times;</button>
					<h2 id="pcModal-title">${t.apply("add")}</h2>
					<form method="post">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="add">
					<div class="form-row">
						<label>${t.apply("country_code")}
							<input type="text" name="countryCode" placeholder="+20" required></label>
						<label>${t.apply("flag_emoji")}
							<input type="text" name="flagEmoji" placeholder="🇪🇬"></label>
					</div>
					<div class="form-row">
						<label>${t.apply("name_ar")}<input type="text" name="nameAr" required></label>
						<label>${t.apply("name_en")}<input type="text" name="nameEn" required></label>
					</div>
					<div class="form-row">
						<label>${t.apply("phone_length")}
							<input type="number" name="phoneLength" min="4" max="15" required></label>
						<label>${t.apply("sort_order")}
							<input type="number" name="sortOrder" value="0"></label>
					</div>
					<div class="form-row">
						<%-- Free text, comma or space separated, as the dashboard's field is. --%>
						<label>${t.apply("phone_prefixes")}
							<input type="text" name="prefixes" placeholder="10, 11, 12, 15"></label>
						<label class="login-remember">
							<input type="checkbox" name="isActive" value="1" checked>
							<span>${t.apply("status_active")}</span></label>
					</div>
					<button type="submit" class="btn btn-blue">${t.apply("save")}</button>
				</form>
				</div>
			</div>
		@endif
	`)
