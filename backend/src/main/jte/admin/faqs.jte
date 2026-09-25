@import com.workin.backend.platformadmin.content.Faq
@import com.workin.backend.platformadmin.web.DashboardSession
@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.hr.ListDisplay
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param List<Faq.Category> categories
@param List<Faq.Item> items
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
<%-- dashboard/pages/faqs/page.php: categories and items as two cards, each
     with its own add form. Both tables list in the order the clients render
     them, which is why sort_order is shown rather than hidden. --%>
@template.admin.layout(
	title = t.apply("nav_faqs"),
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
				<h2 class="data-table-title">${t.apply("faq_categories")} (${categories.size()})</h2>
				<div class="data-table-head__actions">
					@if(canWrite)
						<button type="button" class="btn btn-blue btn-sm"
								onclick="crudOpenAdd('faqCatModal')">+ ${t.apply("add")}</button>
					@endif
				</div>
			</div>
			<div class="table-wrap">
				<table class="tbl">
					<thead>
						<tr>
							<th>#</th>
							<th>${t.apply("name_ar")}</th>
							<th>${t.apply("name_en")}</th>
							<th>${t.apply("sort_order")}</th>
							<th>${t.apply("faq_items_count")}</th>
							<th>${t.apply("status")}</th>
							@template.admin.actionsHeader(t = t)
						</tr>
					</thead>
					<tbody>
						@for(int index = 0; index < categories.size(); index++)
							!{Faq.Category category = categories.get(index);}
							<tr>
								<td class="text-muted">${index + 1}</td>
								<td>${category.nameAr()}</td>
								<td>${category.nameEn()}</td>
								<td>${category.sortOrder()}</td>
								<td>${category.itemCount()}</td>
								<td>@template.admin.statusBadge(status = category.active() ? "active" : "inactive", t = t)</td>
								<td class="col-actions">
									<%-- The confirm names the item count: deleting a category
									     cascades, and the number is the part that surprises.

									     The menu prefix is not decoration: category ids and item
									     ids come from independent sequences and routinely collide,
									     so without one both tables would emit
									     row-actions-menu-1 and each trigger's aria-controls
									     would name the other's menu as often as its own. --%>
									@if(canWrite)
										@template.admin.rowActions(rowId = category.id(), t = t,
												menuPrefix = "faq-category-menu", items = @`
											<%-- D-210: updateCategory existed with nothing posting
											     edit_category, so a category could be deleted but
											     not renamed. --%>
											<button type="button" role="menuitem" class="row-actions__item"
													data-dialog="faq-category-edit"
													data-dialog-id="${category.id()}"
													data-dialog-subject="${category.nameAr()}"
													data-dialog-nameAr="${category.nameAr()}"
													data-dialog-nameEn="${category.nameEn()}"
													data-dialog-sortOrder="${category.sortOrder()}"
													data-dialog-isActive="${category.active() ? "1" : "0"}">${t.apply("edit")}</button>
											<form method="post" class="row-actions__form"
											      data-confirm="${t.apply("faq_delete_category_confirm")}" data-confirm-tone="danger">
												<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
												<input type="hidden" name="action" value="delete_category">
												<input type="hidden" name="id" value="${category.id()}">
												<button type="submit" role="menuitem" class="row-actions__item row-actions__item--danger">${t.apply("delete")}</button>
											</form>
										`)
									@else
										<span class="text-muted">—</span>
									@endif
								</td>
							</tr>
						@endfor
						@if(categories.isEmpty())
							<tr><td colspan="7" class="data-table-empty">${t.apply("no_data")}</td></tr>
						@endif
					</tbody>
				</table>
			</div>
		</div>

		<div class="data-table-card">
			<div class="data-table-head">
				<h2 class="data-table-title">${t.apply("faq_questions")} (${items.size()})</h2>
				<div class="data-table-head__actions">
					@if(canWrite && !categories.isEmpty())
						<button type="button" class="btn btn-blue btn-sm"
								onclick="crudOpenAdd('faqItemModal')">+ ${t.apply("add")}</button>
					@endif
				</div>
			</div>
			<div class="table-wrap">
				<table class="tbl">
					<thead>
						<tr>
							<th>#</th>
							<th>${t.apply("faq_category")}</th>
							<th>${t.apply("question_ar")}</th>
							<th>${t.apply("question_en")}</th>
							<th>${t.apply("sort_order")}</th>
							<th>${t.apply("app_platform")}</th>
							<th>${t.apply("status")}</th>
							@template.admin.actionsHeader(t = t)
						</tr>
					</thead>
					<tbody>
						@for(int index = 0; index < items.size(); index++)
							!{Faq.Item item = items.get(index);}
							<tr>
								<td class="text-muted">${index + 1}</td>
								<td>${arabic ? item.categoryNameAr() : item.categoryNameEn()}</td>
								<%-- Both languages, as legacy shows them: this page is where
								     somebody checks that the Arabic and the English say the
								     same thing, and one column cannot answer that. The answer
								     is long, so it rides in the title. --%>
								<td class="bold">${ListDisplay.strimwidth(item.questionAr(), 60)}</td>
								<td class="text-muted">${ListDisplay.strimwidth(item.questionEn(), 60)}</td>
								<td>${item.sortOrder()}</td>
								<td><span class="badge badge-blue">${t.apply(item.platform().labelKey())}</span></td>
								<td>@template.admin.statusBadge(status = item.active() ? "active" : "inactive", t = t)</td>
								<td class="col-actions">
									@if(canWrite)
										@template.admin.rowActions(rowId = item.id(), t = t,
												menuPrefix = "faq-item-menu", items = @`
											<%-- D-210: updateItem existed with nothing posting
											     edit_item, so a question could be deleted but not
											     corrected. --%>
											<button type="button" role="menuitem" class="row-actions__item"
													data-dialog="faq-item-edit"
													data-dialog-id="${item.id()}"
													data-dialog-subject="${item.questionAr()}"
													data-dialog-categoryId="${item.categoryId()}"
													data-dialog-questionAr="${item.questionAr()}"
													data-dialog-questionEn="${item.questionEn()}"
													data-dialog-answerAr="${item.answerAr()}"
													data-dialog-answerEn="${item.answerEn()}"
													<%-- .stored(), not the enum: the options are stored values
													     ("both"), and setting a select to "BOTH" matches
													     none of them and silently clears it. --%>
													data-dialog-platform="${item.platform().stored()}"
													data-dialog-sortOrder="${item.sortOrder()}"
													data-dialog-isActive="${item.active() ? "1" : "0"}">${t.apply("edit")}</button>
											<form method="post" class="row-actions__form"
											      data-confirm="${t.apply("confirm_delete")}" data-confirm-tone="danger">
												<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
												<input type="hidden" name="action" value="delete_item">
												<input type="hidden" name="id" value="${item.id()}">
												<button type="submit" role="menuitem" class="row-actions__item row-actions__item--danger">${t.apply("delete")}</button>
											</form>
										`)
									@else
										<span class="text-muted">—</span>
									@endif
								</td>
							</tr>
						@endfor
						@if(items.isEmpty())
							<tr><td colspan="8" class="data-table-empty">${t.apply("no_data")}</td></tr>
						@endif
					</tbody>
				</table>
			</div>
		</div>
		@if(canWrite)
			@template.admin.rowDialog(id = "faq-category-edit", action = "edit_category",
					title = t.apply("edit"), csrfParameterName = csrfParameterName,
					csrfToken = csrfToken, t = t, submitLabel = t.apply("save"),
					fields = @`
						<div class="form-row">
							<label for="faq_cat_edit_name_ar">${t.apply("name_ar")}</label>
							<input type="text" id="faq_cat_edit_name_ar" name="nameAr" required data-dialog-field="nameAr">
						</div>
						<div class="form-row">
							<label for="faq_cat_edit_name_en">${t.apply("name_en")}</label>
							<input type="text" id="faq_cat_edit_name_en" name="nameEn" required data-dialog-field="nameEn">
						</div>
						<div class="form-row">
							<label for="faq_cat_edit_sort">${t.apply("sort_order")}</label>
							<input type="number" id="faq_cat_edit_sort" name="sortOrder" data-dialog-field="sortOrder">
						</div>
						<%-- Ticked from the row, as legacy's edit modal sets it
						     (faqs/page.php:260). Rendered ticked, it re-activated
						     every inactive category saved from this dialog. --%>
						<div class="form-row">
							<label><input type="checkbox" name="isActive" value="1" data-dialog-field="isActive">
								<span>${t.apply("status_active")}</span></label>
						</div>
					`)

			@template.admin.rowDialog(id = "faq-item-edit", action = "edit_item",
					title = t.apply("edit"), csrfParameterName = csrfParameterName,
					csrfToken = csrfToken, t = t, submitLabel = t.apply("save"),
					fields = @`
						<div class="form-row">
							<label for="faq_item_edit_category">${t.apply("faq_category")}</label>
							<select id="faq_item_edit_category" name="categoryId" required data-dialog-field="categoryId">
								@for(Faq.Category category : categories)
									<option value="${category.id()}">${arabic ? category.nameAr() : category.nameEn()}</option>
								@endfor
							</select>
						</div>
						<div class="form-row">
							<label for="faq_item_edit_q_ar">${t.apply("question_ar")}</label>
							<textarea id="faq_item_edit_q_ar" name="questionAr" rows="2" required data-dialog-field="questionAr"></textarea>
						</div>
						<div class="form-row">
							<label for="faq_item_edit_q_en">${t.apply("question_en")}</label>
							<textarea id="faq_item_edit_q_en" name="questionEn" rows="2" required data-dialog-field="questionEn"></textarea>
						</div>
						<div class="form-row">
							<label for="faq_item_edit_a_ar">${t.apply("answer_ar")}</label>
							<textarea id="faq_item_edit_a_ar" name="answerAr" rows="4" required data-dialog-field="answerAr"></textarea>
						</div>
						<div class="form-row">
							<label for="faq_item_edit_a_en">${t.apply("answer_en")}</label>
							<textarea id="faq_item_edit_a_en" name="answerEn" rows="4" required data-dialog-field="answerEn"></textarea>
						</div>
						<div class="form-row">
							<label for="faq_item_edit_platform">${t.apply("app_platform")}</label>
							<select id="faq_item_edit_platform" name="platform" data-dialog-field="platform">
								@for(Faq.Platform p : Faq.Platform.offered())
									<option value="${p.stored()}">${t.apply(p.labelKey())}</option>
								@endfor
							</select>
						</div>
						<div class="form-row">
							<label for="faq_item_edit_sort">${t.apply("sort_order")}</label>
							<input type="number" id="faq_item_edit_sort" name="sortOrder" data-dialog-field="sortOrder">
						</div>
						<%-- Ticked from the row, as legacy's edit modal sets it
						     (faqs/page.php:274). --%>
						<div class="form-row">
							<label><input type="checkbox" name="isActive" value="1" data-dialog-field="isActive">
								<span>${t.apply("status_active")}</span></label>
						</div>
					`)

			<%-- Legacy opens this in a modal (faqCatAddModal / faqItemAddModal),
			     behind the "+" button on the table above. Rendered inline it sat
			     open under the table on every visit -- a stack of full-width
			     boxes with no heading of its own. --%>
			<div class="modal-bg" id="faqCatModal">
				<div class="modal" role="dialog" aria-modal="true"
						aria-labelledby="faqCatModal-title">
					<button type="button" class="modal-close"
							aria-label="${t.apply("cancel")}">&times;</button>
					<h2 id="faqCatModal-title">${t.apply("add_faq_category")}</h2>
					<form method="post">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="add_category">
					<%-- faqCatAddModal (faqs/page.php:153-167): one field to a row. --%>
					<div class="form-row">
						<label for="faq_cat_add_name_ar">${t.apply("name_ar")}</label>
						<input type="text" id="faq_cat_add_name_ar" name="nameAr" required>
					</div>
					<div class="form-row">
						<label for="faq_cat_add_name_en">${t.apply("name_en")}</label>
						<input type="text" id="faq_cat_add_name_en" name="nameEn" required>
					</div>
					<div class="form-row">
						<label for="faq_cat_add_sort">${t.apply("sort_order")}</label>
						<input type="number" id="faq_cat_add_sort" name="sortOrder" value="0">
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
		@if(canWrite && !categories.isEmpty())
			<%-- Legacy opens this in a modal (faqCatAddModal / faqItemAddModal),
			     behind the "+" button on the table above. Rendered inline it sat
			     open under the table on every visit -- a stack of full-width
			     boxes with no heading of its own. --%>
			<div class="modal-bg" id="faqItemModal">
				<div class="modal" role="dialog" aria-modal="true"
						aria-labelledby="faqItemModal-title">
					<button type="button" class="modal-close"
							aria-label="${t.apply("cancel")}">&times;</button>
					<h2 id="faqItemModal-title">${t.apply("add_faq_question")}</h2>
					<form method="post">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="add_item">
					<%-- faqItemAddModal (faqs/page.php:186-217): one field to a row, the
					     category asked for rather than defaulted to the first, and the
					     platform labelled and defaulting to both, as legacy's are. --%>
					<div class="form-row">
						<label for="faq_item_add_category">${t.apply("faq_category")}</label>
						<select id="faq_item_add_category" name="categoryId" required>
							<option value="">${t.apply("select")}</option>
							@for(Faq.Category category : categories)
								<option value="${category.id()}">${arabic ? category.nameAr() : category.nameEn()}</option>
							@endfor
						</select>
					</div>
					<div class="form-row">
						<label for="faq_item_add_q_ar">${t.apply("question_ar")}</label>
						<textarea id="faq_item_add_q_ar" name="questionAr" rows="2" required></textarea>
					</div>
					<div class="form-row">
						<label for="faq_item_add_q_en">${t.apply("question_en")}</label>
						<textarea id="faq_item_add_q_en" name="questionEn" rows="2" required></textarea>
					</div>
					<div class="form-row">
						<label for="faq_item_add_a_ar">${t.apply("answer_ar")}</label>
						<textarea id="faq_item_add_a_ar" name="answerAr" rows="4" required></textarea>
					</div>
					<div class="form-row">
						<label for="faq_item_add_a_en">${t.apply("answer_en")}</label>
						<textarea id="faq_item_add_a_en" name="answerEn" rows="4" required></textarea>
					</div>
					<div class="form-row">
						<label for="faq_item_add_platform">${t.apply("app_platform")}</label>
						<select id="faq_item_add_platform" name="platform">
							@for(Faq.Platform p : Faq.Platform.offered())
								<option value="${p.stored()}">${t.apply(p.labelKey())}</option>
							@endfor
						</select>
					</div>
					<div class="form-row">
						<label for="faq_item_add_sort">${t.apply("sort_order")}</label>
						<input type="number" id="faq_item_add_sort" name="sortOrder" value="0">
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
