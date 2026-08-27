package com.workin.legacy.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.workin.legacy.AbstractLegacyMySqlTest;

/**
 * {@link LegacyPayrollFiscalSettings#fiscalPeriodBounds} against real
 * {@code company_settings} rows, not just {@link LegacyPayrollFiscalSettings
 * #computeBounds}'s already-thorough pure-arithmetic coverage in {@link
 * LegacyPayrollFiscalSettingsTest}. That class proves the clamping/default
 * logic once the two raw values are in hand; this proves the plumbing that
 * gets them there actually reaches the same defaults for the two DB-level
 * shapes production data can take: no fiscal-settings rows at all for a
 * company, and a stored value that is itself out of PHP's valid range
 * (only {@code computeBounds}'s <em>argument</em> being out of range was
 * covered before, never a genuinely persisted one).
 */
class LegacyPayrollFiscalSettingsMariaDbTest extends AbstractLegacyMySqlTest {

	private static final long COMPANY_NO_SETTINGS = 21001L;
	private static final long COMPANY_OUT_OF_RANGE_END = 21002L;
	private static final long COMPANY_ZERO_START = 21003L;

	private static LegacyPayrollFiscalSettings fiscalSettings;

	@BeforeAll
	static void prepare() throws Exception {
		DataSource dataSource = dataSourceFor(MARIADB.getDatabaseName());
		fiscalSettings = new LegacyPayrollFiscalSettings(dataSource);
		seed();
	}

	@Test
	void noFiscalSettingsRowsAtAllDefaultToTheFullCalendarMonth() {
		String[] bounds = fiscalSettings.fiscalPeriodBounds(COMPANY_NO_SETTINGS, 2026, 4);
		assertThat(bounds).containsExactly("2026-04-01", "2026-04-30");
	}

	@Test
	void anOutOfRangeStoredEndDayClampsToTheLastCalendarDay() {
		// month_end_day is stored as '99' -- clampDay(99) -> 31, then min'd against April's 30.
		String[] bounds = fiscalSettings.fiscalPeriodBounds(COMPANY_OUT_OF_RANGE_END, 2026, 4);
		assertThat(bounds).containsExactly("2026-04-01", "2026-04-30");
	}

	@Test
	void aZeroStoredStartDayDefaultsLikeAMissingOneWouldHave() {
		// month_start_day is stored as '0', a genuinely persisted value, not an absent setting --
		// LegacyValues.toPhpLong("0") is 0, which must still clamp to 1, not to the raw value.
		String[] bounds = fiscalSettings.fiscalPeriodBounds(COMPANY_ZERO_START, 2026, 4);
		assertThat(bounds).containsExactly("2026-04-01", "2026-04-30");
	}

	private static DataSource dataSourceFor(String database) {
		String url = MARIADB.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
		DriverManagerDataSource source = new DriverManagerDataSource(url, MARIADB.getUsername(), MARIADB.getPassword());
		source.setDriverClassName("org.mariadb.jdbc.Driver");
		return source;
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("""
					INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
					  (21001, 'Fiscal No Settings Co', '+201000021001', 'active', '2025-01-15 09:00:00'),
					  (21002, 'Fiscal Out Of Range Co', '+201000021002', 'active', '2025-01-15 09:00:00'),
					  (21003, 'Fiscal Zero Start Co', '+201000021003', 'active', '2025-01-15 09:00:00')
					""");
			// COMPANY_NO_SETTINGS gets no setting_definitions/company_settings rows at all --
			// forCompany() must see an empty result, not a missing-table error.

			// COMPANY_OUT_OF_RANGE_END: month_end_day stored as '99'.
			st.execute("""
					INSERT INTO setting_definitions (id, setting_key, is_multi) VALUES (21010, 'month_end_day', 0)
					""");
			st.execute("""
					INSERT INTO company_settings (id, company_id, setting_definition_id) VALUES (21010, 21002, 21010)
					""");
			st.execute("""
					INSERT INTO setting_allowed_values (id, setting_definition_id, value, sort_order)
					VALUES (21010, 21010, '99', 0)
					""");
			st.execute("""
					INSERT INTO company_setting_values (company_setting_id, setting_allowed_value_id) VALUES (21010, 21010)
					""");

			// COMPANY_ZERO_START: month_start_day stored as '0'.
			st.execute("""
					INSERT INTO setting_definitions (id, setting_key, is_multi) VALUES (21011, 'month_start_day', 0)
					""");
			st.execute("""
					INSERT INTO company_settings (id, company_id, setting_definition_id) VALUES (21011, 21003, 21011)
					""");
			st.execute("""
					INSERT INTO setting_allowed_values (id, setting_definition_id, value, sort_order)
					VALUES (21011, 21011, '0', 0)
					""");
			st.execute("""
					INSERT INTO company_setting_values (company_setting_id, setting_allowed_value_id) VALUES (21011, 21011)
					""");
		}
	}
}
