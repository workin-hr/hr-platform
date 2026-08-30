package com.workin.legacy.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The EAV settings tables: {@code setting_definitions} (what can be set),
 * {@code setting_allowed_values} (what each may be set to),
 * {@code company_settings} (that a company has set one) and
 * {@code company_setting_values} (which allowed values it chose).
 *
 * <h2>Only two of the four are tenant-scoped, and that is correct</h2>
 * <p>Definitions and allowed values are <b>platform</b> configuration with no
 * {@code company_id} column -- every tenant sees the same catalogue.
 * {@code company_settings} carries the tenant, and
 * {@code company_setting_values} inherits it through its parent. So every
 * mutation below filters on {@code company_id} at the {@code company_settings}
 * row and the child rows are reached only through it.
 */
@Repository
public class LegacySettingsStore {

	/** The definition columns every settings response selects, in legacy's order. */
	private static final String DEFINITION_COLUMNS =
			"id, setting_key, label_ar, label_en, description_ar, description_en,"
					+ " icon_data, is_multi, is_required, sort_order, updated_at";

	private final JdbcTemplate jdbcTemplate;

	public LegacySettingsStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/** One {@code setting_definitions} row. */
	public record Definition(
			long id, String settingKey, String labelAr, String labelEn,
			String descriptionAr, String descriptionEn, String iconData,
			int isMulti, int isRequired, int sortOrder, String updatedAt) {
	}

	/** One {@code setting_allowed_values} row, as the option shape needs it. */
	public record AllowedValue(long definitionId, String value, String labelAr, String labelEn) {
	}

	private static final org.springframework.jdbc.core.RowMapper<Definition> DEFINITION_MAPPER =
			(rs, rowNum) -> new Definition(
					rs.getLong("id"), rs.getString("setting_key"),
					rs.getString("label_ar"), rs.getString("label_en"),
					rs.getString("description_ar"), rs.getString("description_en"),
					rs.getString("icon_data"), rs.getInt("is_multi"), rs.getInt("is_required"),
					rs.getInt("sort_order"), rs.getString("updated_at"));

	public List<Definition> allDefinitions() {
		return jdbcTemplate.query(
				"SELECT " + DEFINITION_COLUMNS + " FROM setting_definitions ORDER BY sort_order ASC, id ASC",
				DEFINITION_MAPPER);
	}

	public Definition definition(long id) {
		List<Definition> rows = jdbcTemplate.query(
				"SELECT " + DEFINITION_COLUMNS + " FROM setting_definitions WHERE id = ?",
				DEFINITION_MAPPER, id);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * {@code SELECT id FROM setting_definitions WHERE setting_key=?} with no
	 * {@code LIMIT} -- {@code setting_key} is unique, so the first row is the
	 * only row.
	 */
	public long definitionIdForKey(String settingKey) {
		List<Long> ids = jdbcTemplate.queryForList(
				"SELECT id FROM setting_definitions WHERE setting_key=?", Long.class, settingKey);
		return ids.isEmpty() ? 0L : ids.get(0);
	}

	/** Allowed values for one definition, in {@code (sort_order, id)} order. */
	public List<AllowedValue> allowedValues(long definitionId) {
		return jdbcTemplate.query(
				"""
				SELECT setting_definition_id, value, label_ar, label_en
				FROM setting_allowed_values
				WHERE setting_definition_id = ?
				ORDER BY sort_order ASC, id ASC""",
				(rs, rowNum) -> new AllowedValue(
						rs.getLong("setting_definition_id"), rs.getString("value"),
						rs.getString("label_ar"), rs.getString("label_en")),
				definitionId);
	}

	/** Allowed values for many definitions at once, grouped by definition. */
	public Map<Long, List<AllowedValue>> allowedValuesFor(List<Long> definitionIds) {
		if (definitionIds.isEmpty()) {
			return Map.of();
		}
		String placeholders = definitionIds.stream().map(id -> "?").collect(Collectors.joining(","));
		List<AllowedValue> rows = jdbcTemplate.query(
				"SELECT setting_definition_id, value, label_ar, label_en"
						+ " FROM setting_allowed_values"
						+ " WHERE setting_definition_id IN (" + placeholders + ")"
						+ " ORDER BY setting_definition_id ASC, sort_order ASC, id ASC",
				(rs, rowNum) -> new AllowedValue(
						rs.getLong("setting_definition_id"), rs.getString("value"),
						rs.getString("label_ar"), rs.getString("label_en")),
				definitionIds.toArray());
		Map<Long, List<AllowedValue>> grouped = new LinkedHashMap<>();
		for (AllowedValue row : rows) {
			grouped.computeIfAbsent(row.definitionId(), key -> new java.util.ArrayList<>()).add(row);
		}
		return grouped;
	}

	/** A company's chosen value, carrying the parent row's id and timestamp. */
	public record Selection(
			long definitionId, long companySettingId, String updatedAt,
			String value, String labelAr, String labelEn) {
	}

	/**
	 * A company's selections across many definitions.
	 *
	 * <p>Ordered by {@code (definition, sav.sort_order, sav.id)}, which is what
	 * makes "the first row of each definition" a stable source for the parent's
	 * id and timestamp -- legacy takes both from whichever selected row it sees
	 * first, so the ordering is load-bearing rather than cosmetic.
	 */
	public List<Selection> selections(long companyId, List<Long> definitionIds) {
		if (definitionIds.isEmpty()) {
			return List.of();
		}
		String placeholders = definitionIds.stream().map(id -> "?").collect(Collectors.joining(","));
		Object[] binds = new Object[definitionIds.size() + 1];
		binds[0] = companyId;
		for (int i = 0; i < definitionIds.size(); i++) {
			binds[i + 1] = definitionIds.get(i);
		}
		return jdbcTemplate.query(
				"SELECT cs.id AS company_setting_id, cs.setting_definition_id, cs.updated_at,"
						+ " sav.value, sav.label_ar, sav.label_en"
						+ " FROM company_settings cs"
						+ " INNER JOIN company_setting_values csv ON csv.company_setting_id = cs.id"
						+ " INNER JOIN setting_allowed_values sav ON sav.id = csv.setting_allowed_value_id"
						+ " WHERE cs.company_id = ? AND cs.setting_definition_id IN (" + placeholders + ")"
						+ " ORDER BY cs.setting_definition_id ASC, sav.sort_order ASC, sav.id ASC",
				(rs, rowNum) -> new Selection(
						rs.getLong("setting_definition_id"), rs.getLong("company_setting_id"),
						rs.getString("updated_at"), rs.getString("value"),
						rs.getString("label_ar"), rs.getString("label_en")),
				binds);
	}

	/** The company_settings row id for one definition, or 0. */
	public long companySettingId(long companyId, long definitionId) {
		List<Long> ids = jdbcTemplate.queryForList(
				"SELECT id FROM company_settings WHERE company_id=? AND setting_definition_id=?",
				Long.class, companyId, definitionId);
		return ids.isEmpty() ? 0L : ids.get(0);
	}

	/** Its {@code updated_at}, needed for the response even when no value is selected. */
	public String companySettingUpdatedAt(long companySettingId) {
		List<String> rows = jdbcTemplate.queryForList(
				"SELECT updated_at FROM company_settings WHERE id=?", String.class, companySettingId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	public long definitionIdForCompanySetting(long companySettingId, long companyId) {
		List<Long> ids = jdbcTemplate.queryForList(
				"SELECT setting_definition_id FROM company_settings WHERE id=? AND company_id=?",
				Long.class, companySettingId, companyId);
		return ids.isEmpty() ? 0L : ids.get(0);
	}

	public boolean definitionIsRequired(long definitionId) {
		List<Integer> rows = jdbcTemplate.queryForList(
				"SELECT is_required FROM setting_definitions WHERE id=?", Integer.class, definitionId);
		return !rows.isEmpty() && rows.get(0) != null && rows.get(0) == 1;
	}

	/** Allowed-value ids for the submitted values, keyed by value. */
	public Map<String, Long> allowedValueIds(long definitionId, List<String> values) {
		if (values.isEmpty()) {
			return Map.of();
		}
		String placeholders = values.stream().map(v -> "?").collect(Collectors.joining(","));
		Object[] binds = new Object[values.size() + 1];
		binds[0] = definitionId;
		for (int i = 0; i < values.size(); i++) {
			binds[i + 1] = values.get(i);
		}
		Map<String, Long> out = new LinkedHashMap<>();
		jdbcTemplate.query(
				"SELECT id, value FROM setting_allowed_values"
						+ " WHERE setting_definition_id=? AND value IN (" + placeholders + ")",
				rs -> { out.put(rs.getString("value"), rs.getLong("id")); },
				binds);
		return out;
	}

	public long insertCompanySetting(long companyId, long definitionId) {
		return com.workin.legacy.LegacyGeneratedKeys.insert(jdbcTemplate,
				"INSERT INTO company_settings (company_id, setting_definition_id) VALUES (?, ?)",
				companyId, definitionId);
	}

	public void touchCompanySetting(long companySettingId, long companyId) {
		jdbcTemplate.update(
				"UPDATE company_settings SET updated_at = CURRENT_TIMESTAMP WHERE id=? AND company_id=?",
				companySettingId, companyId);
	}

	public void deleteCompanySetting(long companySettingId, long companyId) {
		jdbcTemplate.update("DELETE FROM company_settings WHERE id=? AND company_id=?",
				companySettingId, companyId);
	}

	public void deleteCompanySettingValues(long companySettingId) {
		jdbcTemplate.update("DELETE FROM company_setting_values WHERE company_setting_id=?",
				companySettingId);
	}

	public void insertCompanySettingValue(long companySettingId, long allowedValueId) {
		jdbcTemplate.update(
				"INSERT INTO company_setting_values (company_setting_id, setting_allowed_value_id)"
						+ " VALUES (?, ?)",
				companySettingId, allowedValueId);
	}

	// ---- setting_definitions/list.php and setting_allowed_values/list.php ----

	/**
	 * Both list endpoints {@code SELECT *}, so the table's columns <b>are</b>
	 * the wire contract and a schema change is a contract change. Returned as
	 * ordered maps for exactly that reason: naming a subset here would silently
	 * narrow what the endpoint returns.
	 */
	public List<Map<String, Object>> definitionRows(String search, long limit, long offset) {
		StringBuilder sql = new StringBuilder("SELECT * FROM setting_definitions");
		List<Object> binds = new java.util.ArrayList<>();
		if (search != null) {
			sql.append(" WHERE (setting_key LIKE ? OR label_ar LIKE ? OR label_en LIKE ?)");
			String like = "%" + search + "%";
			binds.add(like);
			binds.add(like);
			binds.add(like);
		}
		sql.append(" ORDER BY sort_order ASC, id ASC LIMIT ? OFFSET ?");
		binds.add(limit);
		binds.add(offset);
		return jdbcTemplate.query(sql.toString(),
				com.workin.legacy.LegacyJdbcValues.rowMapper(), binds.toArray());
	}

	public long countDefinitions(String search) {
		if (search == null) {
			Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM setting_definitions", Long.class);
			return total == null ? 0L : total;
		}
		String like = "%" + search + "%";
		Long total = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM setting_definitions"
						+ " WHERE (setting_key LIKE ? OR label_ar LIKE ? OR label_en LIKE ?)",
				Long.class, like, like, like);
		return total == null ? 0L : total;
	}

	public List<Map<String, Object>> allowedValueRows(long definitionId, long limit, long offset) {
		return jdbcTemplate.query(
				"SELECT * FROM setting_allowed_values WHERE setting_definition_id = ?"
						+ " ORDER BY sort_order ASC, id ASC LIMIT ? OFFSET ?",
				com.workin.legacy.LegacyJdbcValues.rowMapper(), definitionId, limit, offset);
	}

	public long countAllowedValues(long definitionId) {
		Long total = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM setting_allowed_values WHERE setting_definition_id=?",
				Long.class, definitionId);
		return total == null ? 0L : total;
	}

	public boolean definitionExists(long definitionId) {
		Long count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM setting_definitions WHERE id=?", Long.class, definitionId);
		return count != null && count > 0;
	}
}
