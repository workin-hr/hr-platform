package com.workin.legacy.attendance.records;

import java.util.List;
import java.util.Locale;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code attendance/export.php}'s two config gates:
 * {@code show_export_overall_sheet} and {@code show_export_fingerprints_sheet},
 * read through {@code app_config_value($key, 'true')} and compared against
 * PHP's own truthy list before the sheet is built.
 *
 * <p>A disabled sheet is {@code fail(FORBIDDEN, 403)} -- the gate runs after
 * authentication and after the {@code type} branch is chosen, so disabling the
 * fingerprints sheet does not fall back to the overall one.
 *
 * <h2>Not cached across requests, deliberately</h2>
 * <p>PHP's {@code app_config_value()} has a static cache, but PHP's process
 * ends with the request, so that cache is per-request. Holding it for the life
 * of a Spring bean would mean an operator flipping the flag never takes effect
 * until a restart -- a behavioural divergence dressed as an optimisation. This
 * is one indexed lookup on a two-column table.
 */
@Component
public class LegacyExportSheetAvailability {

	static final String OVERALL_KEY = "show_export_overall_sheet";
	static final String FINGERPRINTS_KEY = "show_export_fingerprints_sheet";

	/**
	 * {@code in_array($enabledRaw, ['1', 'true', 'yes', 'on'], true)} after
	 * {@code strtolower((string) (app_config_value($key, 'true') ?? 'true'))}.
	 * Anything else -- including an empty value or a missing row that resolves
	 * to something other than the default -- is disabled.
	 */
	private static final List<String> ENABLED_VALUES = List.of("1", "true", "yes", "on");

	private final JdbcTemplate jdbcTemplate;

	public LegacyExportSheetAvailability(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	public boolean overallSheetEnabled() {
		return enabled(OVERALL_KEY);
	}

	public boolean fingerprintsSheetEnabled() {
		return enabled(FINGERPRINTS_KEY);
	}

	private boolean enabled(String key) {
		String raw = configValue(key);
		return ENABLED_VALUES.contains(raw.toLowerCase(Locale.ROOT));
	}

	/** {@code app_config_value($key, 'true')}: the default stands in for a missing or blank row. */
	private String configValue(String key) {
		String value;
		try {
			List<String> rows = jdbcTemplate.queryForList(
					"SELECT config_value FROM configs WHERE config_key = ? LIMIT 1", String.class, key);
			value = rows.isEmpty() ? null : rows.get(0);
		} catch (Throwable ex) { // NOPMD - catch (Throwable $ignored), as PHP does
			value = null;
		}
		if (value == null || value.trim().isEmpty()) {
			return "true";
		}
		return value.trim();
	}
}
