package com.workin.legacy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;
import org.springframework.context.annotation.ScopedProxyMode;

/**
 * Legacy's notion of "today".
 *
 * <p>{@code date('Y-m-d')} is not UTC and not the JVM default: PHP sets the
 * application timezone from the database before any endpoint runs
 * ({@code functions.php:250-259} via {@code config/pdo.php:23-36}) --
 * {@code configs.is_daylight_saving} decides between {@code Etc/GMT-3} (UTC+3)
 * and {@code Etc/GMT-2} (UTC+2), and any failure to read it leaves the +02:00
 * default in place. Employee creation writes {@code hire_date}, the shift
 * assignment's {@code effective_from} and the leave-balance year from that
 * clock, so a wrong timezone silently dates records a day early or late.
 *
 * <p>Request-scoped for the same reason {@link com.workin.legacy.phone.LegacyPhoneCountries}
 * is: PHP resolves this once per request and forgets it, so a moment where the
 * config row is unreadable must not pin the whole JVM to the default offset.
 *
 * <p><b>This class is only half of what legacy does, and the other half is an
 * open Phase 1 cutover blocker (D-083).</b> {@code pdo.php:23-36} also issues
 * {@code SET time_zone} on every legacy connection with the same offset, which
 * decides how {@code TIMESTAMP} columns render, what {@code CURRENT_TIMESTAMP}
 * defaults write, and what {@code NOW()}/{@code CURDATE()} return -- including
 * in this module's own {@code stats.php}. Java does not do that yet. Do not
 * treat this class as closing that requirement.
 */
@Component
@RequestScope(proxyMode = ScopedProxyMode.TARGET_CLASS)
public class LegacyClock {

	/** {@code in_array($normalized, ['1','true','yes','summer','dst'], true)}. */
	private static final java.util.Set<String> DAYLIGHT_SAVING_VALUES =
			java.util.Set.of("1", "true", "yes", "summer", "dst");

	private final JdbcTemplate jdbcTemplate;

	private ZoneOffset offset;

	public LegacyClock(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/** {@code date('Y-m-d')} under legacy's configured offset. */
	public LocalDate today() {
		return LocalDate.now(zoneOffset());
	}

	/**
	 * The full instant {@code strtotime('now')} resolves to, under the same
	 * offset {@link #today()} uses -- one resolution, not two.
	 *
	 * <p>Truncated to seconds on purpose. PHP's observable value is
	 * {@code date('H:i:s', ...)}, which has no sub-second component, and
	 * {@code attendance/create.php} branches on that string being exactly
	 * {@code 00:00:00}. Keeping Java's nanoseconds would make an instant a few
	 * hundred microseconds past midnight compare as non-midnight and classify a
	 * PHP exception-only day as a real punch.
	 */
	public LocalDateTime now() {
		return LocalDateTime.now(zoneOffset()).withNano(0);
	}

	/** {@code date('Y-m-d')} as the string PHP would produce. */
	public String todayAsString() {
		return today().toString();
	}

	/**
	 * The offset {@code applyRuntimeTimezoneFromConfigs()} would have applied.
	 * Any failure keeps +02:00, exactly as PHP's {@code catch (Throwable $ignored)}
	 * does.
	 */
	private ZoneOffset zoneOffset() {
		ZoneOffset cached = offset;
		if (cached != null) {
			return cached;
		}
		ZoneOffset resolved = ZoneOffset.ofHours(2);
		try {
			java.util.List<String> values = jdbcTemplate.queryForList(
					"SELECT config_value FROM configs WHERE config_key = ? LIMIT 1",
					String.class, "is_daylight_saving");
			if (!values.isEmpty() && values.get(0) != null
					&& DAYLIGHT_SAVING_VALUES.contains(values.get(0).trim().toLowerCase(java.util.Locale.ROOT))) {
				resolved = ZoneOffset.ofHours(3);
			}
		} catch (Throwable ex) { // NOPMD - catch (Throwable $ignored), as PHP does
			resolved = ZoneOffset.ofHours(2);
		}
		offset = resolved;
		return resolved;
	}

}
