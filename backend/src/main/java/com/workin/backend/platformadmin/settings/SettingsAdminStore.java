package com.workin.backend.platformadmin.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The settings page's three tabs, each backed by a platform-level table with
 * no {@code company_id} -- which is why the page is administrator-only and why
 * D-176 has no tenant here to anchor to.
 */
@Repository
@Profile("phase1-mysql")
public class SettingsAdminStore {

	private final JdbcTemplate jdbcTemplate;

	public SettingsAdminStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	// ---------------------------------------------------------------- content

	/**
	 * {@code app_content_load_map()}: all three keys, seeded empty, then
	 * overlaid with whatever rows exist.
	 */
	public List<AppContentEntry> appContent() {
		Map<String, AppContentEntry> map = new LinkedHashMap<>();
		for (String key : SettingsCatalog.APP_CONTENT_KEYS) {
			SettingsCatalog.ContentDefinition definition = SettingsCatalog.APP_CONTENT.get(key);
			map.put(key, new AppContentEntry(key, definition.labelAr(), definition.labelEn(),
					definition.icon(), "", "", null));
		}
		this.jdbcTemplate.query(
				"SELECT content_key, content_value_ar, content_value_en, updated_at"
						+ " FROM app_content WHERE content_key IN (?, ?, ?)",
				rs -> {
					String key = rs.getString("content_key");
					AppContentEntry seeded = map.get(key);
					if (seeded != null) {
						map.put(key, new AppContentEntry(key, seeded.labelAr(), seeded.labelEn(),
								seeded.icon(),
								text(rs.getString("content_value_ar")),
								text(rs.getString("content_value_en")),
								rs.getTimestamp("updated_at") == null
										? null : rs.getTimestamp("updated_at").toLocalDateTime()));
					}
				},
				SettingsCatalog.APP_CONTENT_KEYS.toArray());
		return List.copyOf(map.values());
	}

	/**
	 * {@code app_content_save()}'s upsert. The caller has already checked the
	 * key against the allowlist; this writes only the two value columns on a
	 * conflict, leaving the key alone.
	 */
	public void saveAppContent(String key, String valueAr, String valueEn) {
		this.jdbcTemplate.update(
				"INSERT INTO app_content (content_key, content_value_ar, content_value_en)"
						+ " VALUES (?, ?, ?)"
						+ " ON DUPLICATE KEY UPDATE content_value_ar = VALUES(content_value_ar),"
						+ " content_value_en = VALUES(content_value_en)",
				key, valueAr, valueEn);
	}

	// -------------------------------------------------------------- templates

	private static final RowMapper<SettingTemplate.Option> OPTION_MAPPER =
			(rs, rowNum) -> new SettingTemplate.Option(
					rs.getLong("id"),
					rs.getLong("setting_definition_id"),
					rs.getString("value"),
					rs.getString("label_ar"),
					rs.getString("label_en"),
					rs.getInt("sort_order"),
					rs.getInt("companies_using"));

	/**
	 * {@code setting_templates_definitions_with_options()}, including the
	 * per-option usage count.
	 *
	 * <p>Two queries rather than one join, as legacy does, so a definition with
	 * no options still appears.
	 */
	public List<SettingTemplate> templates() {
		List<Object[]> definitions = this.jdbcTemplate.query(
				"SELECT id, setting_key, label_ar, label_en, description_ar, description_en,"
						+ " is_multi, is_required, sort_order"
						+ " FROM setting_definitions ORDER BY sort_order ASC, id ASC",
				(rs, rowNum) -> new Object[] {
					rs.getLong("id"), rs.getString("setting_key"), rs.getString("label_ar"),
					rs.getString("label_en"), rs.getString("description_ar"),
					rs.getString("description_en"), rs.getInt("is_multi"),
					rs.getInt("is_required"), rs.getInt("sort_order") });
		if (definitions.isEmpty()) {
			return List.of();
		}

		List<Long> ids = definitions.stream().map(row -> (Long) row[0]).toList();
		String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
		List<SettingTemplate.Option> options = this.jdbcTemplate.query(
				"SELECT sav.id, sav.setting_definition_id, sav.value, sav.label_ar, sav.label_en,"
						+ " sav.sort_order," + USAGE_SUBQUERY + " AS companies_using"
						+ " FROM setting_allowed_values sav"
						+ " WHERE sav.setting_definition_id IN (" + placeholders + ")"
						+ " ORDER BY sav.setting_definition_id ASC, sav.sort_order ASC, sav.id ASC",
				OPTION_MAPPER, ids.toArray());

		List<SettingTemplate> result = new ArrayList<>();
		for (Object[] row : definitions) {
			long id = (Long) row[0];
			result.add(new SettingTemplate(id, (String) row[1], (String) row[2], (String) row[3],
					(String) row[4], (String) row[5],
					(Integer) row[6] == 1, (Integer) row[7] == 1, (Integer) row[8],
					options.stream().filter(option -> option.definitionId() == id).toList()));
		}
		return result;
	}

	/**
	 * How many distinct companies have selected an option.
	 *
	 * <p>{@code COUNT(DISTINCT cs.company_id)}, not a row count: one company
	 * choosing the same option twice is one company, and the number reaches the
	 * user in the refusal message.
	 */
	private static final String USAGE_SUBQUERY =
			" (SELECT COUNT(DISTINCT cs.company_id) FROM company_setting_values csv"
					+ " INNER JOIN company_settings cs ON cs.id = csv.company_setting_id"
					+ " WHERE csv.setting_allowed_value_id = sav.id)";

	public int optionUsage(long optionId) {
		if (optionId <= 0) {
			return 0;
		}
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(DISTINCT cs.company_id) FROM company_setting_values csv"
						+ " INNER JOIN company_settings cs ON cs.id = csv.company_setting_id"
						+ " WHERE csv.setting_allowed_value_id = ?",
				Integer.class, optionId);
		return count == null ? 0 : count;
	}

	public boolean definitionExists(long id) {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM setting_definitions WHERE id = ?", Integer.class, id);
		return count != null && count > 0;
	}

	/** The option's own row, or null. Used for the value-pinning rule. */
	public SettingTemplate.Option option(long id) {
		List<SettingTemplate.Option> rows = this.jdbcTemplate.query(
				"SELECT sav.id, sav.setting_definition_id, sav.value, sav.label_ar, sav.label_en,"
						+ " sav.sort_order," + USAGE_SUBQUERY + " AS companies_using"
						+ " FROM setting_allowed_values sav WHERE sav.id = ? LIMIT 1",
				OPTION_MAPPER, id);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** Whether another option of the same definition already holds this value. */
	public boolean optionValueTaken(long definitionId, String value, long excludeId) {
		StringBuilder sql = new StringBuilder(
				"SELECT COUNT(*) FROM setting_allowed_values"
						+ " WHERE setting_definition_id = ? AND value = ?");
		List<Object> params = new ArrayList<>(List.of(definitionId, value));
		if (excludeId > 0) {
			sql.append(" AND id <> ?");
			params.add(excludeId);
		}
		Integer count = this.jdbcTemplate.queryForObject(
				sql.toString(), Integer.class, params.toArray());
		return count != null && count > 0;
	}

	/**
	 * {@code setting_templates_edit_definition()}'s update: five fields, and
	 * never {@code setting_key}.
	 */
	public void updateDefinition(long id, String labelAr, String labelEn,
			String descriptionAr, String descriptionEn, int sortOrder) {
		this.jdbcTemplate.update(
				"UPDATE setting_definitions SET label_ar = ?, label_en = ?, description_ar = ?,"
						+ " description_en = ?, sort_order = ? WHERE id = ?",
				labelAr, labelEn, descriptionAr, descriptionEn, sortOrder, id);
	}

	public long insertOption(long definitionId, String value, String labelAr, String labelEn,
			int sortOrder) {
		this.jdbcTemplate.update(
				"INSERT INTO setting_allowed_values (setting_definition_id, value, label_ar,"
						+ " label_en, sort_order) VALUES (?, ?, ?, ?, ?)",
				definitionId, value, labelAr, labelEn, sortOrder);
		Long id = this.jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		return id == null ? 0 : id;
	}

	/**
	 * {@code setting_templates_edit_option()}'s update.
	 *
	 * <p>{@code setting_definition_id} is not in the statement at all. Legacy
	 * unsets it from the field list before updating, so an option cannot be
	 * moved to another definition by an edit -- the same shape as D-176's rule
	 * about ownership, applied to a key that is not a tenant.
	 */
	public void updateOption(long id, String value, String labelAr, String labelEn, int sortOrder) {
		this.jdbcTemplate.update(
				"UPDATE setting_allowed_values SET value = ?, label_ar = ?, label_en = ?,"
						+ " sort_order = ? WHERE id = ?",
				value, labelAr, labelEn, sortOrder, id);
	}

	public void deleteOption(long id) {
		this.jdbcTemplate.update("DELETE FROM setting_allowed_values WHERE id = ?", id);
	}

	// ----------------------------------------------------------------- system

	/**
	 * {@code configs_load_map()}: every defined key, seeded with its default,
	 * then overlaid from the table. A row whose key is not defined is ignored.
	 */
	public Map<String, String> configs() {
		Map<String, String> map = new LinkedHashMap<>();
		SettingsCatalog.CONFIGS.forEach((key, definition) ->
				map.put(key, definition.defaultValue()));
		this.jdbcTemplate.query("SELECT config_key, config_value FROM configs", rs -> {
			String key = rs.getString("config_key");
			if (map.containsKey(key)) {
				map.put(key, text(rs.getString("config_value")));
			}
		});
		return map;
	}

	public void saveConfig(String key, String value) {
		this.jdbcTemplate.update(
				"INSERT INTO configs (config_key, config_value) VALUES (?, ?)"
						+ " ON DUPLICATE KEY UPDATE config_value = VALUES(config_value)",
				key, value);
	}

	private static String text(String value) {
		return value == null ? "" : value;
	}

}
