package com.workin.legacy.companies;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.workin.legacy.LegacyJdbcValues;

/**
 * Frozen {@code companies} row access for Wave 12.10 ({@code company/*.php}).
 *
 * <p>{@code public_row($row)} returns the PDO row minus {@code sensitive_response_keys()}
 * ({@code helpers/public_row.php:10}) -- {@code password_hash} and
 * {@code token_version}, the latter not a {@code companies} column at all, so
 * stripping it here is a harmless no-op exactly as PHP's {@code unset()} is.
 */
@Repository
public class LegacyCompanyStore {

	private static final List<String> SENSITIVE_KEYS = List.of("password_hash", "token_version");

	private final JdbcTemplate jdbc;

	public LegacyCompanyStore(DataSource legacyDataSource) {
		this.jdbc = new JdbcTemplate(legacyDataSource);
	}

	public Map<String, Object> findById(long companyId) {
		return single(jdbc.query("SELECT * FROM companies WHERE id=?", this::row, companyId));
	}

	/** {@code company_code_is_taken()} ({@code company_code_helper.php:29-42}). */
	public boolean companyCodeIsTaken(String normalizedCode, long excludeCompanyId) {
		Long count = jdbc.queryForObject(
				"SELECT COUNT(*) FROM companies WHERE UPPER(company_code)=? AND id<>?",
				Long.class, normalizedCode, excludeCompanyId);
		return count != null && count > 0;
	}

	/** {@code company_email_is_taken()} ({@code functions.php:31-43}). */
	public boolean companyEmailIsTaken(String email, long excludeCompanyId) {
		Long count = jdbc.queryForObject(
				"SELECT COUNT(*) FROM companies WHERE email=? AND id<>?",
				Long.class, email, excludeCompanyId);
		return count != null && count > 0;
	}

	public boolean companyActivityExists(long id) {
		return exists("SELECT id FROM company_activities WHERE id=?", id);
	}

	public boolean companyTitleExists(long id) {
		return exists("SELECT id FROM company_titles WHERE id=?", id);
	}

	public boolean companySizeExists(long id) {
		return exists("SELECT id FROM company_sizes WHERE id=?", id);
	}

	/**
	 * {@code update.php}'s dynamic {@code UPDATE companies SET ... WHERE id=?}
	 * -- only the columns the request actually carried are written, matching
	 * {@code $update_fields}/{@code $params}.
	 */
	public void updateColumns(long companyId, Map<String, Object> columns) {
		if (columns.isEmpty()) {
			return;
		}
		List<String> assignments = new ArrayList<>();
		List<Object> params = new ArrayList<>();
		for (Map.Entry<String, Object> column : columns.entrySet()) {
			assignments.add(column.getKey() + "=?");
			params.add(column.getValue());
		}
		params.add(companyId);
		jdbc.update("UPDATE companies SET " + String.join(", ", assignments) + " WHERE id=?", params.toArray());
	}

	private boolean exists(String sql, long id) {
		Long count = jdbc.queryForObject("SELECT COUNT(*) FROM (" + sql + ") x", Long.class, id);
		return count != null && count > 0;
	}

	private Map<String, Object> row(ResultSet rs, int rowNum) throws SQLException {
		ResultSetMetaData meta = rs.getMetaData();
		Map<String, Object> row = new LinkedHashMap<>();
		for (int i = 1; i <= meta.getColumnCount(); i++) {
			String label = meta.getColumnLabel(i);
			if (SENSITIVE_KEYS.contains(label)) {
				continue;
			}
			row.put(label, LegacyJdbcValues.read(rs, i, meta.getColumnType(i)));
		}
		return row;
	}

	private static Map<String, Object> single(List<Map<String, Object>> rows) {
		return rows.isEmpty() ? null : rows.getFirst();
	}
}
