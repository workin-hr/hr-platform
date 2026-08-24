package com.workin.legacy.attendance.spreadsheet;

import java.time.LocalDate;

import javax.sql.DataSource;

import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyPhpConfigDate;
import com.workin.legacy.LegacyValues;

/**
 * {@code attendance_excel_import_is_available()},
 * {@code attendance_excel_import_available_from()} and
 * {@code attendance_excel_import_available_from_display()}
 * ({@code hr-legacy/apis/helpers/xlsx_parser.php:433-459}) -- the date gate
 * {@code attendance/import_excel.php} applies before it looks at the upload.
 *
 * <h2>One read per request, because PHP has one</h2>
 * <p>{@code app_config_value()} ({@code configs_helper.php:7-30}) keeps a
 * {@code static $cache} keyed by config key, so within a single PHP request the
 * value is read from {@code configs} once and every later call returns the
 * cached copy. That matters on the refusal path specifically, which reads the
 * same key twice -- once to decide availability and once to build the
 * {@code {date}} placeholder. Two independent reads could disagree mid-request
 * and produce "not available until X" while having decided on a different X.
 * Request-scoped here for exactly that reason, following {@link LegacyClock}'s
 * precedent rather than inventing a cache of its own.
 *
 * <h2>What the cache stores, and what it returns</h2>
 * <p>PHP caches {@code null} for every failure mode -- an SQL error, a missing
 * row, a NULL value and a blank string all become {@code null} -- and a trimmed
 * string otherwise; the {@code $default} is then substituted at each call
 * rather than being cached. The one caller passes {@code ''} as its default, so
 * the observable value is a trimmed string or the empty string.
 *
 * <h2>The `configs` table is not a new boundary</h2>
 * <p>{@link LegacyClock} already issues the identical single-key read against
 * {@code configs}, so this crosses nothing Phase 1 had not already crossed.
 * D-091's {@code company_settings} freeze is a different table and is untouched.
 */
@Component
@RequestScope(proxyMode = ScopedProxyMode.TARGET_CLASS)
public class LegacyAttendanceExcelImportAvailability {

	/** The single {@code configs.config_key} this gate reads. */
	private static final String CONFIG_KEY = "attendance_excel_import_available_from";

	/** {@code '—'} -- the em dash {@code available_from_display()} falls back to. */
	private static final String NO_DATE = "—";

	private final JdbcTemplate jdbcTemplate;
	private final LegacyClock clock;

	private boolean read;
	private String cached;

	public LegacyAttendanceExcelImportAvailability(DataSource legacyDataSource, LegacyClock clock) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
		this.clock = clock;
	}

	/**
	 * {@code attendance_excel_import_is_available()}: false when the config
	 * value does not parse at all, otherwise "is today on or after it".
	 *
	 * <p>Note the double parse in PHP -- {@code available_from()} parses the raw
	 * value and re-formats it as {@code Y-m-d}, and this function parses
	 * <em>that</em> again. Reproduced, because the round trip is not always the
	 * identity: a value that rolls comes back as the rolled date, and a value
	 * that does not parse comes back as the trimmed raw string, which then
	 * fails the second parse in a way the first did not (a raw
	 * {@code -0001-11-30} produced by {@code 0000-00-00} is one such value).
	 *
	 * <p>{@code new DateTimeImmutable('today')} is the application clock, which
	 * {@link LegacyClock#today()} already reproduces -- not a new D-083
	 * dependency and not a session-timezone change.
	 */
	public boolean isAvailable() {
		LocalDate from = LegacyPhpConfigDate.parse(availableFrom());
		return from != null && !clock.today().isBefore(from);
	}

	/**
	 * {@code attendance_excel_import_available_from()}: the config value parsed
	 * and re-formatted as {@code Y-m-d}, or the trimmed raw value when it does
	 * not parse.
	 */
	public String availableFrom() {
		String raw = configValue();
		LocalDate parsed = LegacyPhpConfigDate.parse(raw);
		// `$dt ? $dt->format('Y-m-d') : trim($raw)`. LocalDate#toString is
		// ISO_LOCAL_DATE, which pads the year to four digits and signs a
		// negative one the same way PHP's 'Y' does.
		return parsed != null ? parsed.toString() : LegacyValues.phpTrim(raw);
	}

	/**
	 * {@code attendance_excel_import_available_from_display()}: {@code j/n/Y},
	 * or the unparsed string, or an em dash when there is nothing at all.
	 */
	public String availableFromDisplay() {
		String from = availableFrom();
		LocalDate parsed = LegacyPhpConfigDate.parse(from);
		if (parsed == null) {
			return from.isEmpty() ? NO_DATE : from;
		}
		return LegacyPhpConfigDate.formatDayMonthYear(parsed);
	}

	/**
	 * {@code app_config_value($key, '')} with its static cache.
	 *
	 * <p>The cached value is the normalized one: a trimmed non-blank string, or
	 * {@code null} for every other outcome. {@code null} then resolves to the
	 * caller's default, which here is the empty string.
	 */
	private String configValue() {
		if (!read) {
			cached = readConfigValue();
			read = true;
		}
		return cached == null ? "" : cached;
	}

	private String readConfigValue() {
		String value;
		try {
			java.util.List<String> rows = jdbcTemplate.queryForList(
					"SELECT config_value FROM configs WHERE config_key = ? LIMIT 1",
					String.class, CONFIG_KEY);
			// db_value() returns false for no row; both that and a NULL value
			// cache null in PHP, and so does a thrown query.
			value = rows.isEmpty() ? null : rows.get(0);
		} catch (Throwable ex) { // NOPMD - catch (Throwable $ignored), as PHP does
			return null;
		}
		if (value == null) {
			return null;
		}
		String normalized = LegacyValues.phpTrim(value);
		return normalized.isEmpty() ? null : normalized;
	}

}
