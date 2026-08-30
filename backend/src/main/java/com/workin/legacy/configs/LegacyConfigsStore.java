package com.workin.legacy.configs;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The two queries {@code configs/get.php} issues -- neither filtered by company.
 *
 * <p>{@code configs} is a <b>global</b> table: it has no {@code company_id}
 * column at all ({@code mysql_workin.schema.sql:373-377}), so there is no
 * tenant scope to apply and none is missing. This is the one legacy table read
 * by an endpoint where the absence of a company filter is correct rather than a
 * defect, which is why it is stated here instead of left to be re-derived by
 * the next reader.
 */
@Repository
public class LegacyConfigsStore {

	private final JdbcTemplate jdbcTemplate;

	public LegacyConfigsStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/**
	 * {@code get_one("SELECT config_key, config_value FROM configs WHERE config_key = ?", [$key])}.
	 *
	 * <p>Returns {@code null} when no row matches, which the caller renders as a
	 * null {@code config_value} rather than a 404 -- PHP's {@code ?? null}.
	 * A row that exists always has a value: {@code config_value} is
	 * {@code NOT NULL} in the schema, so null in the response means "no such
	 * key" and nothing else.
	 *
	 * <p>No {@code LIMIT 1} on purpose, matching the PHP. It would be harmless
	 * -- {@code config_key} carries a UNIQUE index -- but the query is quoted in
	 * the inventory and the two should read alike.
	 */
	public String value(String key) {
		return jdbcTemplate.query(
				"SELECT config_key, config_value FROM configs WHERE config_key = ?",
				rs -> rs.next() ? rs.getString("config_value") : null, key);
	}

	/**
	 * {@code get_all("SELECT config_key, config_value FROM configs")}, folded
	 * into PHP's {@code $configs[$row['config_key']] = $row['config_value']}.
	 *
	 * <p><b>Insertion-ordered, and unordered by SQL.</b> PHP has no
	 * {@code ORDER BY} here and a PHP associative array keeps insertion order,
	 * which {@code json_encode} then emits in that order. A
	 * {@link LinkedHashMap} reproduces both halves; a {@code HashMap} would
	 * silently reorder the JSON object's keys, and adding an {@code ORDER BY}
	 * would change the order legacy actually produces rather than preserve it.
	 */
	public Map<String, String> all() {
		Map<String, String> configs = new LinkedHashMap<>();
		jdbcTemplate.query("SELECT config_key, config_value FROM configs",
				rs -> { configs.put(rs.getString("config_key"), rs.getString("config_value")); });
		return configs;
	}

}
