@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.content.BroadcastAudience
@import com.workin.backend.platformadmin.content.BroadcastStore
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param BroadcastAudience[] audiences
@param int reach
@param List<BroadcastStore.SentBroadcast> recent
@param boolean actionsEnabled
@param boolean factorBound
@param String errorKey = null
@param Integer sentCount = null
@param String currentPage
@param String lang
@param com.workin.backend.platformadmin.web.DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
!{boolean canWrite = actionsEnabled && factorBound;}
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
		@elseif(!factorBound)
			<div class="flash flash-warning">${t.apply("mfa_required_for_actions")}</div>
		@endif


		<div class="data-table-card">
			<div class="data-table-head">
				<h2 class="data-table-title">${t.apply("recent_activities")}</h2>
				<div class="data-table-head__actions">
					@if(canWrite)
						<button type="button" class="btn btn-blue btn-sm"
								onclick="crudOpenAdd('notifModal')">+ ${t.apply("nav_notifications")}</button>
					@endif
				</div>
			</div>
			<div class="table-wrap">
				<table class="tbl">
					<thead>
						<tr>
							<th>${t.apply("notif_title")}</th>
							<th>${t.apply("notif_body")}</th>
							<th>${t.apply("notif_audience")}</th>
							<th>${t.apply("date")}</th>
						</tr>
					</thead>
					<tbody>
						@for(BroadcastStore.SentBroadcast broadcast : recent)
							<tr>
								<td>${broadcast.title()}</td>
								<td>${broadcast.body()}</td>
								<td>${broadcast.recipients()}</td>
								<td>${broadcast.sentAt()}</td>
							</tr>
						@endfor
						@if(recent.isEmpty())
							<tr><td colspan="4" class="data-table-empty">${t.apply("no_data")}</td></tr>
						@endif
					</tbody>
				</table>
			</div>
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
						<label class="login-remember">
							<input type="checkbox" name="confirmBroadcast" value="1" required>
							<span>${t.apply("confirm_broadcast")} — ${t.apply("notif_audience_all_employees")}: ${reach}</span>
						</label>
					</div>
					<button type="submit" class="btn btn-blue">${t.apply("send_notification")}</button>
				</form>
				</div>
			</div>
		@endif
		</div>
	`)
