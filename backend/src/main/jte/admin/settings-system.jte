@import java.util.List
@import java.util.Map
@import java.util.function.Function
@import com.workin.backend.platformadmin.settings.SettingsCatalog
@param List<String> groupOrder
@param Map<String, List<SettingsCatalog.ConfigDefinition>> configGroups
@param Map<String, String> inputValues
@param String csrfParameterName
@param String csrfToken
@param boolean canWrite
@param Function<String, String> t
<%-- dashboard/pages/settings/_tab_system.php. One form for every group: the
     save writes every defined key, so submitting a subset would blank the
     rest. The page always posts the whole tab, which is why that is safe here
     and why the service reproduces it rather than guarding against it. --%>
<div class="settings-page">
	<form method="POST" action="/admin/settings" class="settings-form">
		<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
		<input type="hidden" name="action" value="save_configs">

		@for(String group : groupOrder)
			@if(!configGroups.get(group).isEmpty())
				<section class="settings-group-card">
					<h3 class="settings-group-card__title">${t.apply(SettingsCatalog.groupLabelKey(group))}</h3>
					<div class="settings-fields-grid">
						@for(SettingsCatalog.ConfigDefinition definition : configGroups.get(group))
							!{String id = "cfg_" + definition.key().replaceAll("[^a-z0-9_]", "_");}
							!{String value = inputValues.get(definition.key());}
							<div class="settings-field">
								<label for="${id}">${t.apply(definition.labelKey())}</label>
								@if("boolean".equals(definition.type()))
									<select name="${definition.key()}" id="${id}">
										<option value="1" selected="${"1".equals(value)}">${t.apply("yes")}</option>
										<option value="0" selected="${!"1".equals(value)}">${t.apply("no")}</option>
									</select>
								@elseif("number".equals(definition.type()))
									<input type="number" name="${definition.key()}" id="${id}"
									       value="${value}" min="0" step="1">
								@elseif("date".equals(definition.type()))
									<input type="date" name="${definition.key()}" id="${id}" value="${value}">
								@else
									<input type="${"url".equals(definition.type()) ? "url" : "text"}"
									       name="${definition.key()}" id="${id}" value="${value}">
								@endif
								@if(definition.hintKey() != null)
									<span class="settings-field__hint">${t.apply(definition.hintKey())}</span>
								@endif
								<code class="settings-field__key">${definition.key()}</code>
							</div>
						@endfor
					</div>
				</section>
			@endif
		@endfor

		<div class="settings-form-footer">
			<button type="submit" class="btn btn-blue" disabled="${!canWrite}">${t.apply("settings_save_all")}</button>
		</div>
	</form>
</div>
