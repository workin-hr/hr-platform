@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.content.GuideVideo
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param List<GuideVideo> videos
@param boolean actionsEnabled
@param String errorKey = null
@param String currentPage
@param String lang
@param com.workin.backend.platformadmin.web.DashboardSession session
@param Function<String, String> t
@param Function<String, Boolean> available
!{boolean canWrite = actionsEnabled;}
<%-- dashboard/pages/guide_videos/page.php.

     PHP puts add and edit behind two modals filled by a script that reads a
     JSON data attribute off each row. The same capability is here as an add
     form and a per-row disclosure, which needs no JavaScript and no row data
     duplicated into an attribute. Nothing about what can be submitted changes.

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
							<th class="col-actions">${t.apply("actions")}</th>
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
								<td><span class="badge ${row.active() ? "badge-green" : "badge-gray"}">
									${t.apply(row.active() ? "status_active" : "status_inactive")}</span></td>
								<td class="col-actions">
									@if(canWrite)
										@template.admin.rowActions(rowId = row.id(), t = t, items = @`
											<form method="post" class="row-actions__form"
											      onsubmit="return confirm('${t.apply("confirm_delete")}')">
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
							@if(canWrite)
								<tr>
									<td colspan="7">
										<details>
											<summary>${t.apply("edit_guide_video")}</summary>
											<form method="post">
												<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
												<input type="hidden" name="action" value="edit">
												<input type="hidden" name="id" value="${row.id()}">
												<div class="form-row">
													<label>${t.apply("title_ar")}
														<input type="text" name="title_ar" required
														       value="${row.titleAr()}"></label>
													<label>${t.apply("title_en")}
														<input type="text" name="title_en" required
														       value="${row.titleEn()}"></label>
													<label>${t.apply("guide_video_file")}
														<input type="text" name="video" dir="ltr" required
														       placeholder="1.mp4" value="${row.video()}"></label>
													<label>${t.apply("sort_order")}
														<input type="number" name="sort_order"
														       value="${row.sortOrder()}"></label>
													<label>
														<input type="checkbox" name="is_active" value="1"
														       checked="${row.active()}">
														<span>${t.apply("status_active")}</span></label>
												</div>
												<p class="text-muted">${t.apply("guide_video_hint")}</p>
												<button type="submit" class="btn btn-blue">${t.apply("save")}</button>
											</form>
										</details>
									</td>
								</tr>
							@endif
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
					<div class="form-row">
						<label>${t.apply("title_ar")}
							<input type="text" name="title_ar" required></label>
						<label>${t.apply("title_en")}
							<input type="text" name="title_en" required></label>
						<label>${t.apply("guide_video_file")}
							<input type="text" name="video" dir="ltr" required placeholder="1.mp4"></label>
						<label>${t.apply("sort_order")}
							<input type="number" name="sort_order" value="0"></label>
						<label>
							<input type="checkbox" name="is_active" value="1" checked>
							<span>${t.apply("status_active")}</span></label>
					</div>
					<p class="text-muted">${t.apply("guide_video_hint")}</p>
					<button type="submit" class="btn btn-blue">${t.apply("add")}</button>
				</form>
				</div>
			</div>
		@endif
	`)
