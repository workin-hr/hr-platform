package com.workin.legacy.workforce;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The {@code leave_balance} slice {@code requests/approve.php}'s side effects
 * read and write ({@code helpers/request_actions_helper.php:58-121}). Not the
 * full {@code leave_balances/*.php} module -- that CRUD surface (analyze,
 * import, generate, stats, template) is a separate, later slice.
 */
@Repository
public class LegacyLeaveBalanceStore {

	/** {@code company_setting_selected_values($company_id, 'monthly_leave_accrual')}. */
	private static final String MONTHLY_LEAVE_ACCRUAL_VALUES = """
			SELECT sav.value
			FROM setting_definitions sd
			INNER JOIN company_settings cs ON cs.setting_definition_id = sd.id AND cs.company_id = ?
			INNER JOIN company_setting_values csv ON csv.company_setting_id = cs.id
			INNER JOIN setting_allowed_values sav ON sav.id = csv.setting_allowed_value_id
			WHERE sd.setting_key = 'monthly_leave_accrual'
			ORDER BY sav.sort_order ASC, sav.id ASC""";

	/** {@code $leave_days_vals[0] ?? 21.0}: the setting's own default, unwritten companies included. */
	private static final double DEFAULT_ANNUAL_LEAVE_DAYS = 21.0;

	/** PHP's {@code (float)} string cast: the longest leading numeric prefix, or 0.0 with none. */
	private static final Pattern LEADING_NUMBER =
			Pattern.compile("^\\s*[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");

	private final JdbcTemplate jdbcTemplate;

	public LegacyLeaveBalanceStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/** {@code total_days}/{@code used_days} for the row, or null when none exists for this employee/year. */
	public Row totalAndUsed(long employeeId, int year) {
		List<Row> rows = jdbcTemplate.query(
				"SELECT total_days, used_days FROM leave_balance WHERE employee_id = ? AND year = ?",
				(rs, index) -> new Row(rs.getDouble("total_days"), rs.getDouble("used_days")),
				employeeId, year);
		return rows.isEmpty() ? null : rows.get(0);
	}

	public boolean exists(long employeeId, int year) {
		Long count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM leave_balance WHERE employee_id = ? AND year = ?",
				Long.class, employeeId, year);
		return count != null && count > 0;
	}

	/** {@code UPDATE leave_balance SET used_days = used_days + ? WHERE employee_id = ? AND year = ?}. */
	public void incrementUsedDays(long employeeId, int year, int days) {
		jdbcTemplate.update(
				"UPDATE leave_balance SET used_days = used_days + ? WHERE employee_id = ? AND year = ?",
				days, employeeId, year);
	}

	public void insert(long employeeId, int year, double totalDays, int usedDays) {
		jdbcTemplate.update(
				"INSERT INTO leave_balance (employee_id, year, total_days, used_days) VALUES (?, ?, ?, ?)",
				employeeId, year, totalDays, usedDays);
	}

	/**
	 * {@code company_setting_selected_values($company_id, 'monthly_leave_accrual')[0] ?? 21.0}.
	 * Whatever the first selected value parses to, taken exactly as PHP's
	 * {@code (float)} cast reads it -- an unparseable value casts to 0.0, not
	 * to the 21.0 default, because the setting was still "selected".
	 */
	public double monthlyLeaveAccrualDefault(long companyId) {
		List<String> values = jdbcTemplate.queryForList(MONTHLY_LEAVE_ACCRUAL_VALUES, String.class, companyId);
		for (String value : values) {
			if (value != null && !value.isEmpty()) {
				return phpFloatCast(value);
			}
		}
		return DEFAULT_ANNUAL_LEAVE_DAYS;
	}

	private static double phpFloatCast(String value) {
		Matcher matcher = LEADING_NUMBER.matcher(value);
		return matcher.find() ? Double.parseDouble(matcher.group()) : 0.0;
	}

	public record Row(double totalDays, double usedDays) {
	}

}
