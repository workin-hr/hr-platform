@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.content.BroadcastAudience
@import com.workin.backend.platformadmin.content.BroadcastStore
@import com.workin.backend.platformadmin.web.DashboardListFilters
@import com.workin.backend.platformadmin.web.DashboardPage
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param BroadcastAudience[] audiences
@param int reach
@param DashboardPage<BroadcastStore.NotificationRow> result
@param DashboardListFilters filters
@param String recipient
@param String dateFrom
@param String dateTo
@param boolean actionsEnabled
@param String errorKey = null
@param Integer sentCount = null
@param String currentPage
@param String lang
@param com.workin.backend.platformadmin.web.DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
!{boolean canWrite = actionsEnabled;}
<%-- dashboard/pages/notifications/page.php, admin half. The reach is shown
     beside the audience because it is the number that makes this a decision
     -- nothing un-sends a notification. --%>
@template.admin.layout(
	title = t.apply("nav_notifications"),
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentAdminPhone = currentAdminPhone,
	currentPage = currentPage,
	lang = lang,
	session = session,
	t = t,
	available = available,
	pageStyles = java.util.List.of("hr-pages.css", "leave-balance-form.css"),
	body = @`
		@if(errorKey != null)
			<div class="flash flash-error">${t.apply(errorKey)}</div>
		@endif
		@if(sentCount != null)
			<div class="flash flash-success">${t.apply("sent_ok")} (${sentCount})</div>
		@endif
		@if(!actionsEnabled)
			<div class="flash flash-warning">${t.apply("admin_actions_disabled")}</div>
		@endif


		<%-- notifications_list_filters(): company, free text over title and body,
		     the recipient kind, and a date range over created_at. --%>
		<div class="toolbar page-toolbar page-toolbar-filters">
			<form method="GET" class="toolbar-form toolbar-form--labeled">
				@if(!session.isScopedToOneCompany())
					<div class="filter-field">
						<label class="filter-field__label" for="notif_company">${t.apply("company")}</label>
						<input type="number" id="notif_company" name="company_id" min="0"
						       placeholder="${t.apply("filter_all")}"
						       value="${filters.companyId() > 0 ? String.valueOf(filters.companyId()) : ""}">
					</div>
				@endif
				<div class="filter-field">
					<label class="filter-field__label" for="notif_search">${t.apply("search")}</label>
					<input type="search" id="notif_search" name="search"
					       placeholder="${t.apply("notif_search_placeholder")}"
					       value="${filters.search()}">
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="notif_recipient">${t.apply("recipient")}</label>
					<select id="notif_recipient" name="recipient">
						<option value="" selected="${!"employee".equals(recipient) && !"company".equals(recipient)}">${t.apply("filter_all")}</option>
						<option value="employee" selected="${"employee".equals(recipient)}">${t.apply("recipient_employee")}</option>
						<option value="company" selected="${"company".equals(recipient)}">${t.apply("recipient_company")}</option>
					</select>
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="notif_from">${t.apply("from_date")}</label>
					<input type="date" id="notif_from" name="date_from" value="${dateFrom}">
				</div>
				<div class="filter-field">
					<label class="filter-field__label" for="notif_to">${t.apply("to_date")}</label>
					<input type="date" id="notif_to" name="date_to" value="${dateTo}">
				</div>
				<button type="submit" class="btn btn-blue">${t.apply("search")}</button>
			</form>
		</div>

		<div class="data-table-card">
			<div class="data-table-head">
				<h2 class="data-table-title">${t.apply("nav_notifications")} (${result.total()})</h2>
				<div class="data-table-head__actions">
					@if(canWrite)
						<button type="button" class="btn btn-blue btn-sm"
								onclick="crudOpenAdd('notifModal')">+ ${t.apply("nav_notifications")}</button>
					@endif
				</div>
			</div>
			<div class="table-wrap">
				<%-- One row per recipient, as the table itself is. The page used
				     to list this surface's own sends grouped by title, which is a
				     useful summary and not what the dashboard shows: it hid every
				     notification the apps and the HR pages write, and with them the
				     read flag and the delete. --%>
				<table class="tbl">
					<thead>
						<tr>
							<th>#</th>
							@if(!session.isScopedToOneCompany())<th>${t.apply("company")}</th>@endif
							<th>${t.apply("recipient")}</th>
							<th>${t.apply("notif_title")}</th>
							<th>${t.apply("notif_body")}</th>
							<th>${t.apply("is_read")}</th>
							<th>${t.apply("date")}</th>
							<th>${t.apply("actions")}</th>
						</tr>
					</thead>
					<tbody>
						@for(int index = 0; index < result.data().size(); index++)
							!{BroadcastStore.NotificationRow row = result.data().get(index);}
							<tr>
								<td class="text-muted">${result.from() + index}</td>
								@if(!session.isScopedToOneCompany())
									<td class="text-muted">${row.companyName() == null ? "—" : row.companyName()}</td>
								@endif
								<td>${row.toCompany() ? t.apply("recipient_company")
									: row.toName() == null || row.toName().isBlank() ? "—" : row.toName()}</td>
								<td class="bold">${row.title()}</td>
								<td class="text-muted tbl-clip" title="${row.body() == null ? "" : row.body()}">${row.body() == null || row.body().isBlank() ? "—" : row.body()}</td>
								<%-- badge('1'/'0') in the PHP: the dashboard's yes/no pair,
								     not a status word. --%>
								<td>
									<span class="badge ${row.read() ? "badge-green" : "badge-gray"}">
										${t.apply(row.read() ? "yes" : "no")}</span>
								</td>
								<td class="text-muted" dir="ltr">${row.createdAtDisplay()}</td>
								<td class="col-actions">
									@if(canWrite)
										@template.admin.rowActions(rowId = row.id(), t = t, items = @`
											<form method="post" class="row-actions__form" role="none"
											      onsubmit="return confirm('${t.apply("confirm_delete")}')">
												<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
												<input type="hidden" name="action" value="delete">
												<input type="hidden" name="id" value="${row.id()}">
												<button type="submit" role="menuitem"
														class="row-actions__item row-actions__item--danger">${t.apply("delete")}</button>
											</form>
										`)
									@else
										<span class="text-muted">—</span>
									@endif
								</td>
							</tr>
						@endfor
						@if(result.isEmpty())
							<tr><td colspan="${session.isScopedToOneCompany() ? 7 : 8}" class="data-table-empty">${t.apply("no_data")}</td></tr>
						@endif
					</tbody>
				</table>
			</div>
			!{java.util.SequencedMap<String, String> carry =
					new java.util.LinkedHashMap<>(filters.asQueryParameters());}
			@if(!recipient.isEmpty())
				!{carry.put("recipient", recipient);}
			@endif
			@if(!dateFrom.isEmpty())
				!{carry.put("date_from", dateFrom);}
			@endif
			@if(!dateTo.isEmpty())
				!{carry.put("date_to", dateTo);}
			@endif
			@template.admin.pager(basePath = "/admin/notifications", result = result,
					carry = carry, t = t)
		@if(canWrite)
			<%-- Legacy opens this form in a modal, behind the "+" button above.
			     Rendered inline it sat open over the table on every visit, whether
			     or not anyone was adding anything. Same form, same fields, same
			     POST; .modal-bg is position:fixed, so it lives at the end of the
			     document and out of the reading order. --%>
			<div class="modal-bg" id="notifModal">
				<div class="modal" role="dialog" aria-modal="true" aria-labelledby="notifModal-title">
					<button type="button" class="modal-close"
							aria-label="${t.apply("cancel")}">&times;</button>
					<h2 id="notifModal-title">${t.apply("nav_notifications")}</h2>
					<form method="post"
				      onsubmit="return confirm('${t.apply("confirm_broadcast")}')">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<div class="form-row">
						<label>${t.apply("notif_audience")}
							<select name="audience" required>
								@for(BroadcastAudience audience : audiences)
									<option value="${audience.submitted()}">${t.apply(audience.labelKey())}</option>
								@endfor
							</select></label>
						<%-- Only read for the company audience; the server ignores it otherwise. --%>
						<label>${t.apply("company")}
							<input type="number" name="companyId" min="1"></label>
					</div>
					<div class="form-row">
						<label>${t.apply("notif_title")}<input type="text" name="title" required></label>
					</div>
					<div class="form-row">
						<label>${t.apply("notif_body")}<textarea name="body" rows="3"></textarea></label>
					</div>
					<div class="form-row">
						<label>
							<input type="checkbox" name="confirmBroadcast" value="1" required>
							<span>${t.apply("confirm_broadcast")} — ${t.apply("notif_audience_all_employees")}: ${reach}</span>
						</label>
					</div>
					<%-- The save sits in a form-footer so it stays put while the
					     fields scroll: .modal is the scroll container, and a form
					     longer than the box otherwise pushes its own button out of
					     reach. Legacy leaves it loose here and carries the same
					     problem; the class is legacy's own. --%>
					<div class="form-footer">
						<button type="submit" class="btn btn-blue">${t.apply("send_notification")}</button>
					</div>
				</form>
				</div>
			</div>
		@endif
		</div>
	`)
