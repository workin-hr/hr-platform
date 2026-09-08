@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.content.Faq
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param List<Faq.Category> categories
@param List<Faq.Item> items
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
<%-- dashboard/pages/faqs/page.php: categories and items as two cards, each
     with its own add form. Both tables list in the order the clients render
     them, which is why sort_order is shown rather than hidden. --%>
@template.admin.layout(
	title = t.apply("nav_faqs"),
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentAdminPhone = currentAdminPhone,
	factorBound = factorBound,
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
							<th class="col-actions"><span class="sr-only">${t.apply("actions")}</span></th>
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
								<td><span class="badge ${category.active() ? "badge-green" : "badge-gray"}">
									${t.apply(category.active() ? "status_active" : "status_inactive")}</span></td>
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
											<form method="post" class="row-actions__form"
											      onsubmit="return confirm('${t.apply("faq_delete_category_confirm")}')">
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
							<th class="col-actions"><span class="sr-only">${t.apply("actions")}</span></th>
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
								<td class="tbl-clip" title="${item.answerAr()}">${item.questionAr()}</td>
								<td class="tbl-clip" title="${item.answerEn()}">${item.questionEn()}</td>
								<td>${item.sortOrder()}</td>
								<td><span class="badge badge-blue">${item.platform().stored()}</span></td>
								<td><span class="badge ${item.active() ? "badge-green" : "badge-gray"}">
									${t.apply(item.active() ? "status_active" : "status_inactive")}</span></td>
								<td class="col-actions">
									@if(canWrite)
										@template.admin.rowActions(rowId = item.id(), t = t,
												menuPrefix = "faq-item-menu", items = @`
											<form method="post" class="row-actions__form"
											      onsubmit="return confirm('${t.apply("confirm_delete")}')">
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
			<%-- Legacy opens this in a modal (faqCatAddModal / faqItemAddModal),
			     behind the "+" button on the table above. Rendered inline it sat
			     open under the table on every visit -- a stack of full-width
			     boxes with no heading of its own. --%>
			<div class="modal-bg" id="faqCatModal">
				<div class="modal" role="dialog" aria-modal="true"
						aria-labelledby="faqCatModal-title">
					<button type="button" class="modal-close"
							aria-label="${t.apply("cancel")}">&times;</button>
					<h2 id="faqCatModal-title">${t.apply("faq_categories")}</h2>
					<form method="post">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="add_category">
					<div class="form-row">
						<label>${t.apply("name_ar")}<input type="text" name="nameAr" required></label>
						<label>${t.apply("name_en")}<input type="text" name="nameEn" required></label>
						<label>${t.apply("sort_order")}<input type="number" name="sortOrder" value="0"></label>
						<label class="login-remember">
							<input type="checkbox" name="isActive" value="1" checked>
							<span>${t.apply("status_active")}</span></label>
					</div>
					<button type="submit" class="btn btn-blue">${t.apply("add")}</button>
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
					<h2 id="faqItemModal-title">${t.apply("faq_questions")}</h2>
					<form method="post">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="add_item">
					<div class="form-row">
						<label>${t.apply("faq_category")}
							<select name="categoryId" required>
								@for(Faq.Category category : categories)
									<option value="${category.id()}">${arabic ? category.nameAr() : category.nameEn()}</option>
								@endfor
							</select></label>
						<label>${t.apply("app_platform")}
							<select name="platform">
								@for(Faq.Platform p : Faq.Platform.values())
									<option value="${p.stored()}">${p.stored()}</option>
								@endfor
							</select></label>
						<label>${t.apply("sort_order")}<input type="number" name="sortOrder" value="0"></label>
					</div>
					<div class="form-row">
						<label>${t.apply("question_ar")}<input type="text" name="questionAr" required></label>
						<label>${t.apply("question_en")}<input type="text" name="questionEn" required></label>
					</div>
					<div class="form-row">
						<label>${t.apply("answer_ar")}<textarea name="answerAr" rows="3" required></textarea></label>
						<label>${t.apply("answer_en")}<textarea name="answerEn" rows="3" required></textarea></label>
					</div>
					<div class="form-row">
						<label class="login-remember">
							<input type="checkbox" name="isActive" value="1" checked>
							<span>${t.apply("status_active")}</span></label>
					</div>
					<button type="submit" class="btn btn-blue">${t.apply("add")}</button>
				</form>
				</div>
			</div>
		@endif
	`)
