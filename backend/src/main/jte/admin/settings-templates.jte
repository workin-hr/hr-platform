@import java.util.List
@import java.util.function.Function
@import com.workin.backend.platformadmin.settings.SettingTemplate
@param List<SettingTemplate> templates
@param String csrfParameterName
@param String csrfToken
@param boolean canWrite
@param String lang
@param Function<String, String> t
<%-- dashboard/pages/settings/_tab_setting_templates.php.

     Two rules here are load-bearing rather than cosmetic, and both are about
     an option companies have already chosen: its stored value cannot change,
     and it cannot be deleted. The count in the badge is how many companies,
     and it is the same number the refusal message carries. --%>
<div class="setting-templates-tab">

	<p class="text-muted" style="margin:0 0 16px">${t.apply("setting_templates_hint")}</p>

	@if(templates.isEmpty())
		<div class="data-table-card">
			<p class="data-table-empty">${t.apply("no_data")}</p>
		</div>
	@endif

	@for(SettingTemplate definition : templates)
		!{String label = "ar".equals(lang)
			? (definition.labelAr() == null || definition.labelAr().isBlank()
				? definition.settingKey() : definition.labelAr())
			: (definition.labelEn() == null || definition.labelEn().isBlank()
				? definition.settingKey() : definition.labelEn());}
		<details class="data-table-card setting-templates-panel">
			<summary class="data-table-head setting-templates-panel__summary">
				<div class="setting-templates-panel__title-wrap">
					<span class="setting-templates-panel__chevron" aria-hidden="true"></span>
					<div>
						<h2 class="data-table-title">${label}</h2>
						<div class="text-muted setting-templates-panel__meta">
							<code>${definition.settingKey()}</code>
							@if(definition.multi()) · ${t.apply("setting_option_multi")}@endif
							@if(definition.required()) · ${t.apply("setting_option_required")}@endif
						</div>
					</div>
				</div>
				<div class="data-table-head__actions setting-templates-panel__actions">
					<button type="button" class="btn btn-sm btn-outline" data-setting-definition-edit
					        data-definition-id="${definition.id()}"
					        data-setting-key="${definition.settingKey()}"
					        data-label-ar="${definition.labelAr()}"
					        data-label-en="${definition.labelEn()}"
					        data-description-ar="${definition.descriptionAr()}"
					        data-description-en="${definition.descriptionEn()}"
					        data-sort-order="${definition.sortOrder()}">${t.apply("edit")}</button>
					<button type="button" class="btn btn-blue btn-sm" data-setting-option-add
					        data-definition-id="${definition.id()}"
					        data-definition-label="${label}">+ ${t.apply("add_setting_option")}</button>
				</div>
			</summary>
			<div class="setting-templates-panel__body">
			<div class="data-table-wrap">
			<table class="data-table">
				<thead>
					<tr>
						<th>${t.apply("setting_option_value")}</th>
						<th>${t.apply("name_ar")}</th>
						<th>${t.apply("name_en")}</th>
						<th>${t.apply("sort_order")}</th>
						<th>${t.apply("setting_option_companies_using")}</th>
						<th>${t.apply("actions")}</th>
					</tr>
				</thead>
				<tbody>
				@if(definition.options().isEmpty())
					<tr><td colspan="6" class="data-table-empty">${t.apply("no_data")}</td></tr>
				@else
					@for(SettingTemplate.Option option : definition.options())
					<tr>
						<td class="bold"><code>${option.value()}</code></td>
						<td>${option.labelAr()}</td>
						<td>${option.labelEn()}</td>
						<td class="col-center">${option.sortOrder()}</td>
						<td class="col-center">
							@if(option.inUse())
								<span class="badge badge-yellow">${option.companiesUsing()}</span>
							@else
								<span class="text-muted">0</span>
							@endif
						</td>
						<td class="col-actions">
							<div class="actions">
								<button type="button" class="btn btn-sm btn-outline" data-setting-option-edit
								        data-option-id="${option.id()}"
								        data-definition-id="${definition.id()}"
								        data-value="${option.value()}"
								        data-label-ar="${option.labelAr()}"
								        data-label-en="${option.labelEn()}"
								        data-sort-order="${option.sortOrder()}"
								        data-in-use="${option.inUse()}">${t.apply("edit")}</button>
								<%-- Offered only when nothing depends on it. The
								     service refuses either way; hiding the button
								     keeps the page from inviting a refusal. --%>
								@if(!option.inUse() && canWrite)
									<form method="POST" action="/admin/settings" class="inline-form"
									      onsubmit="return confirm('${t.apply("confirm_delete")}')">
										<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
										<input type="hidden" name="action" value="delete_option">
										<input type="hidden" name="id" value="${option.id()}">
										<button type="submit" class="btn btn-red btn-sm">${t.apply("delete")}</button>
									</form>
								@endif
							</div>
						</td>
					</tr>
					@endfor
				@endif
				</tbody>
			</table>
			</div>
			</div>
		</details>
	@endfor
</div>

<div class="modal-bg" id="settingDefinitionModal">
	<div class="modal">
		<button type="button" class="modal-close" data-setting-definition-close>&times;</button>
		<h2>${t.apply("edit_setting_definition")}</h2>
		<form method="POST" action="/admin/settings" id="settingDefinitionForm">
			<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
			<input type="hidden" name="action" value="edit_definition">
			<input type="hidden" name="id" id="settingDefinitionId" value="">
			<div class="form-row">
				<label>${t.apply("setting_definition_key")}</label>
				<%-- Shown, never posted: the key is the identity the rest of the
				     platform selects on, and no edit path writes it. --%>
				<input type="text" id="settingDefinitionKey" disabled dir="ltr">
				<small class="text-muted">${t.apply("setting_definition_key_readonly")}</small>
			</div>
			<div class="form-row">
				<label>${t.apply("name_ar")} *</label>
				<input type="text" name="label_ar" id="settingDefinitionLabelAr" required>
			</div>
			<div class="form-row">
				<label>${t.apply("name_en")} *</label>
				<input type="text" name="label_en" id="settingDefinitionLabelEn" required dir="ltr">
			</div>
			<div class="form-row">
				<label>${t.apply("description_ar")}</label>
				<textarea name="description_ar" id="settingDefinitionDescAr" rows="3"></textarea>
			</div>
			<div class="form-row">
				<label>${t.apply("description_en")}</label>
				<textarea name="description_en" id="settingDefinitionDescEn" rows="3" dir="ltr"></textarea>
			</div>
			<div class="form-row">
				<label>${t.apply("sort_order")}</label>
				<input type="number" name="sort_order" id="settingDefinitionSort" value="0">
			</div>
			<div class="form-footer">
				<button type="button" class="btn btn-gray" data-setting-definition-close>${t.apply("cancel")}</button>
				<button type="submit" class="btn btn-blue" disabled="${!canWrite}">${t.apply("save")}</button>
			</div>
		</form>
	</div>
</div>

<div class="modal-bg" id="settingOptionModal">
	<div class="modal">
		<button type="button" class="modal-close" data-setting-option-close>&times;</button>
		<h2 id="settingOptionModalTitle">${t.apply("add_setting_option")}</h2>
		<p class="text-muted" id="settingOptionModalDef" style="margin-top:-8px;margin-bottom:12px"></p>
		<form method="POST" action="/admin/settings" id="settingOptionForm">
			<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
			<input type="hidden" name="action" id="settingOptionAction" value="add_option">
			<input type="hidden" name="id" id="settingOptionId" value="">
			<input type="hidden" name="setting_definition_id" id="settingOptionDefinitionId" value="">
			<div class="form-row">
				<label>${t.apply("setting_option_value")} *</label>
				<input type="text" name="value" id="settingOptionValue" required dir="ltr" maxlength="120">
			</div>
			<div class="form-row">
				<label>${t.apply("name_ar")}</label>
				<input type="text" name="label_ar" id="settingOptionLabelAr">
			</div>
			<div class="form-row">
				<label>${t.apply("name_en")}</label>
				<input type="text" name="label_en" id="settingOptionLabelEn" dir="ltr">
			</div>
			<div class="form-row">
				<label>${t.apply("sort_order")}</label>
				<input type="number" name="sort_order" id="settingOptionSort" value="0">
			</div>
			<div class="form-footer">
				<button type="button" class="btn btn-gray" data-setting-option-close>${t.apply("cancel")}</button>
				<button type="submit" class="btn btn-blue" disabled="${!canWrite}">${t.apply("save")}</button>
			</div>
		</form>
	</div>
</div>
