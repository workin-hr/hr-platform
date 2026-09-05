package com.workin.backend.platformadmin.settings;

import java.util.List;

/**
 * One setting definition and the options companies may choose from
 * ({@code setting_templates_definitions_with_options()}).
 *
 * <p>{@code settingKey} is the identity the rest of the platform selects on
 * and is deliberately absent from every edit path -- the tab renames a
 * definition, it does not re-key it.
 *
 * @param companiesUsing on an {@link Option}, how many distinct companies have
 *     selected it. It is what blocks a delete and what pins the option's
 *     stored value on an edit.
 */
public record SettingTemplate(
		long id,
		String settingKey,
		String labelAr,
		String labelEn,
		String descriptionAr,
		String descriptionEn,
		boolean multi,
		boolean required,
		int sortOrder,
		List<Option> options) {

	public record Option(
			long id,
			long definitionId,
			String value,
			String labelAr,
			String labelEn,
			int sortOrder,
			int companiesUsing) {

		/**
		 * An option a company has chosen cannot have its stored value changed,
		 * and cannot be deleted. Labels and sort order stay editable.
		 */
		public boolean inUse() {
			return this.companiesUsing > 0;
		}
	}

}
