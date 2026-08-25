package com.workin.legacy.payroll;

import java.util.List;
import java.util.Locale;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.workin.legacy.LegacyValues;

/**
 * A third bounded, read-only {@code company_settings} reader (D-091's
 * pattern again): the calculation engine slice of Wave 12.9 needs exactly
 * {@code overtime_rate} and {@code pay_overtime}, and nothing else. Kept
 * separate from {@link LegacyPayrollFiscalSettings} rather than folded in --
 * that class's own javadoc states its bound as "the only two keys this
 * reader answers for", and blurring a second, unrelated pair of keys into
 * it would make neither class's name describe its actual scope.
 */
@Component
public class LegacyPayrollOvertimeSettings {

	public enum Key {
		/** {@code CompanySettingEnum::OVERTIME_RATE->value} ({@code config/enums.php:120}). */
		OVERTIME_RATE("overtime_rate"),
		/** {@code CompanySettingEnum::PAY_OVERTIME->value} ({@code config/enums.php:121}). */
		PAY_OVERTIME("pay_overtime");

		private final String settingKey;

		Key(String settingKey) {
			this.settingKey = settingKey;
		}
	}

	/** Identical query to {@code LegacyWeeklyOffDays}/{@code LegacyPayrollFiscalSettings} -- D-091's structural bound duplicates it per reader rather than exposing a general accessor. */
	private static final String SELECTED_VALUES = """
			SELECT sav.value
			FROM setting_definitions sd
			INNER JOIN company_settings cs ON cs.setting_definition_id = sd.id AND cs.company_id = ?
			INNER JOIN company_setting_values csv ON csv.company_setting_id = cs.id
			INNER JOIN setting_allowed_values sav ON sav.id = csv.setting_allowed_value_id
			WHERE sd.setting_key = ?
			ORDER BY sav.sort_order ASC, sav.id ASC""";

	private final JdbcTemplate jdbcTemplate;

	public LegacyPayrollOvertimeSettings(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	public List<String> forCompany(long companyId, Key key) {
		if (companyId <= 0) {
			return List.of();
		}
		List<String> values = jdbcTemplate.queryForList(SELECTED_VALUES, String.class, companyId, key.settingKey);
		return values.stream()
				.map(value -> value == null ? "" : value)
				.filter(value -> !value.isEmpty())
				.toList();
	}

	/**
	 * {@code payroll_overtime_multiplier_from_setting()} ({@code payroll_calculation.php:125-138}):
	 * a raw value {@code <= 0} (including unset) defaults to 1.25; a value
	 * {@code > 10} is treated as a percentage ({@code 125 -> 1.25}); otherwise
	 * the raw value is the multiplier already.
	 */
	public double overtimeMultiplier(long companyId) {
		List<String> vals = forCompany(companyId, Key.OVERTIME_RATE);
		return multiplierFromRaw(vals.isEmpty() ? 125.0 : toPhpDouble(vals.get(0)));
	}

	/** The pure half, split out for testing without a database (mirrors {@code LegacyPayrollFiscalSettings}). */
	static double multiplierFromRaw(double raw) {
		if (raw <= 0) {
			return 1.25;
		}
		return raw > 10 ? round4(raw / 100.0) : raw;
	}

	/**
	 * {@code payroll_company_pays_overtime()} ({@code payroll_calculation.php:140-149}):
	 * unset defaults to {@code true}; otherwise {@code {1, true, yes, on}}
	 * (case-insensitive, trimmed) is {@code true} and everything else is
	 * {@code false}.
	 */
	public boolean companyPaysOvertime(long companyId) {
		List<String> vals = forCompany(companyId, Key.PAY_OVERTIME);
		return vals.isEmpty() || paysOvertimeFromRaw(vals.get(0));
	}

	/** The pure half, split out for testing without a database. */
	static boolean paysOvertimeFromRaw(String raw) {
		String value = LegacyValues.phpTrim(raw).toLowerCase(Locale.ROOT);
		return value.equals("1") || value.equals("true") || value.equals("yes") || value.equals("on");
	}

	private static double toPhpDouble(String value) {
		return LegacyValues.toPhpDecimal(value).doubleValue();
	}

	private static double round4(double value) {
		return Math.round(value * 10000.0) / 10000.0;
	}
}
