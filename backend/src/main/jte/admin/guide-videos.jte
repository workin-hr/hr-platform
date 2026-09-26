@import com.workin.backend.platformadmin.content.GuideVideo
@import com.workin.backend.platformadmin.web.DashboardSession
@import java.util.List
@import java.util.function.Function
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param List<GuideVideo> videos
@param boolean actionsEnabled
@param String errorKey = null
@param String currentPage
@param String lang
@param DashboardSession session
@param Function<String, String> t
@param String flash = null
@param String flashType = "success"
@param Function<String, Boolean> available
!{boolean canWrite = actionsEnabled;}
<%-- dashboard/pages/guide_videos/page.php.

     PHP puts add and edit behind two modals filled by a script that reads a
     JSON data attribute off each row. Here too: add opens its own modal, and
     edit is the row menu's item, opening one shared rowDialog that
     row-dialog.js fills from the item's data-dialog-* attributes (D-287; it
     was a per-row <details> form). Nothing about what can be submitted changes.

     The filename column is dir="ltr" because it is never Arabic, and the
     titles are, so a shared direction would render one of them backwards. --%>
@template.admin.layout(
	title = t.apply("nav_guide_videos"),
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
				<h2 class="data-table-title">${t.apply("nav_guide_videos")} (${videos.size()})</h2>
				<div class="data-table-head__actions">
					@if(canWrite)
						<button type="button" class="btn btn-blue btn-sm"
								onclick="crudOpenAdd('gvModal')">+ ${t.apply("add_guide_video")}</button>
					@endif
				</div>
			</div>
			<div class="table-wrap">
				<table class="tbl">
					<thead>
						<tr>
							<th>#</th>
							<th>${t.apply("title_ar")}</th>
							<th>${t.apply("title_en")}</th>
							<th>${t.apply("guide_video_file")}</th>
							<th>${t.apply("sort_order")}</th>
							<th>${t.apply("status")}</th>
							@template.admin.actionsHeader(t = t)
						</tr>
					</thead>
					<tbody>
						@for(int index = 0; index < videos.size(); index++)
							!{GuideVideo row = videos.get(index);}
							<tr>
								<td>${index + 1}</td>
								<td class="bold">${row.titleAr()}</td>
								<td>${row.titleEn()}</td>
								<td dir="ltr">${row.video().isEmpty() ? "—" : row.video()}</td>
								<td class="col-center">${row.sortOrder()}</td>
								<td>@template.admin.statusBadge(status = row.active() ? "active" : "inactive", t = t)</td>
								<td class="col-actions">
									@if(canWrite)
										@template.admin.rowActions(rowId = row.id(), t = t, items = @`
											<button type="button" role="menuitem" class="row-actions__item"
													data-dialog="gv-edit"
													data-dialog-id="${row.id()}"
													data-dialog-subject="${row.titleAr()}"
													data-dialog-titleAr="${row.titleAr()}"
													data-dialog-titleEn="${row.titleEn()}"
													data-dialog-video="${row.video()}"
													data-dialog-sortOrder="${row.sortOrder()}"
													data-dialog-isActive="${row.active() ? "1" : "0"}">${t.apply("edit")}</button>
											<form method="post" class="row-actions__form"
											      data-confirm="${t.apply("confirm_delete")}" data-confirm-tone="danger">
												<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
												<input type="hidden" name="action" value="delete">
												<input type="hidden" name="id" value="${row.id()}">
												<button type="submit" role="menuitem" class="row-actions__item row-actions__item--danger">${t.apply("delete")}</button>
											</form>
										`)
									@else
										<span class="text-muted">—</span>
									@endif
								</td>
							</tr>
						@endfor
						@if(videos.isEmpty())
							<tr><td colspan="7" class="data-table-empty">${t.apply("no_data")}</td></tr>
						@endif
					</tbody>
				</table>
			</div>

		</div>
		@if(canWrite)
			<%-- Legacy opens this in a modal (gvAddModal), behind the "+" button
			     above. Rendered inline it sat open under the table on every
			     visit. --%>
			<div class="modal-bg" id="gvModal">
				<div class="modal" role="dialog" aria-modal="true" aria-labelledby="gvModal-title">
					<button type="button" class="modal-close"
							aria-label="${t.apply("cancel")}">&times;</button>
					<h2 id="gvModal-title">${t.apply("add_guide_video")}</h2>
					<form method="post">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="add">
					<%-- gvAddModal (guide_videos/page.php:91-123): one field to a row, the file's
					     hint under its field. --%>
					<div class="form-row">
						<label for="gv_add_title_ar">${t.apply("title_ar")}</label>
						<input type="text" id="gv_add_title_ar" name="title_ar" required>
					</div>
					<div class="form-row">
						<label for="gv_add_title_en">${t.apply("title_en")}</label>
						<input type="text" id="gv_add_title_en" name="title_en" required>
					</div>
					<div class="form-row">
						<label for="gv_add_video">${t.apply("guide_video_file")}</label>
						<input type="text" id="gv_add_video" name="video" dir="ltr" required placeholder="1.mp4">
						<p class="text-muted">${t.apply("guide_video_hint")}</p>
					</div>
					<div class="form-row">
						<label for="gv_add_sort">${t.apply("sort_order")}</label>
						<input type="number" id="gv_add_sort" name="sort_order" value="0">
					</div>
					<div class="form-row">
						<label><input type="checkbox" name="is_active" value="1" checked>
							<span>${t.apply("status_active")}</span></label>
					</div>
					<div class="form-footer">
						<button type="submit" class="btn btn-blue">${t.apply("save")}</button>
					</div>
				</form>
				</div>
			</div>

			<%-- Edit, from the row's menu. It was a <details> row under every
			     video, which doubled the table's height and put a form in the
			     list; one dialog serves every row (rowDialog.jte). --%>
			@template.admin.rowDialog(id = "gv-edit", action = "edit",
					title = t.apply("edit_guide_video"), csrfParameterName = csrfParameterName,
					csrfToken = csrfToken, t = t, submitLabel = t.apply("save"),
					fields = @`
						<div class="form-row">
							<label for="gv_edit_title_ar">${t.apply("title_ar")}</label>
							<input type="text" id="gv_edit_title_ar" name="title_ar" required data-dialog-field="titleAr">
						</div>
						<div class="form-row">
							<label for="gv_edit_title_en">${t.apply("title_en")}</label>
							<input type="text" id="gv_edit_title_en" name="title_en" required data-dialog-field="titleEn">
						</div>
						<div class="form-row">
							<label for="gv_edit_video">${t.apply("guide_video_file")}</label>
							<input type="text" id="gv_edit_video" name="video" dir="ltr" required placeholder="1.mp4"
							       data-dialog-field="video">
							<p class="text-muted">${t.apply("guide_video_hint")}</p>
						</div>
						<div class="form-row">
							<label for="gv_edit_sort">${t.apply("sort_order")}</label>
							<input type="number" id="gv_edit_sort" name="sort_order" data-dialog-field="sortOrder">
						</div>
						<div class="form-row">
							<label><input type="checkbox" name="is_active" value="1" data-dialog-field="isActive">
								<span>${t.apply("status_active")}</span></label>
						</div>
					`)
		@endif
	`)
