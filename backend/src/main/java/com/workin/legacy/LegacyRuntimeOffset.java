package com.workin.legacy;

import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;

/**
 * {@code configs.is_daylight_saving} to legacy's runtime UTC offset.
 *
 * <p>The decision is one line of PHP repeated in two places
 * ({@code config/pdo.php:24-35} and {@code functions.php:250-259}), and it is
 * extracted here for exactly that reason: two Java callers need it, they have
 * different lifecycles, and neither should own the other's concern.
 * {@link LegacyClock} is request-scoped and answers "what date is it";
 * {@code LegacySessionDataSource} runs on connection checkout and answers "what
 * must this session be set to". Sharing the grammar keeps them from drifting;
 * sharing a bean would tie a datasource to a request scope.
 *
 * <p>Pure, dependency-free and stateless on purpose. It performs no query and
 * knows nothing about JDBC -- the caller reads the row, this decides what it
 * means.
 */
public final class LegacyRuntimeOffset {

	/** {@code in_array($normalized, ['1','true','yes','summer','dst'], true)}. */
	private static final Set<String> DAYLIGHT_SAVING_VALUES =
			Set.of("1", "true", "yes", "summer", "dst");

	/** The offset PHP starts from and keeps whenever the config cannot be read. */
	public static final ZoneOffset DEFAULT = ZoneOffset.ofHours(2);

	/** The offset a recognised daylight-saving value selects. */
	public static final ZoneOffset DAYLIGHT_SAVING = ZoneOffset.ofHours(3);

	private LegacyRuntimeOffset() {
	}

	/**
	 * {@code strtolower(trim((string) $value))} matched against the five
	 * recognised spellings.
	 *
	 * <p>A null row -- PHP's {@code fetchColumn()} returning {@code false} --
	 * keeps the default, as does any value outside the set. The comparison is
	 * exact after normalising, so {@code "TRUE"} and {@code " dst "} select
	 * +03:00 while {@code "0"}, {@code "on"} and {@code "yes please"} do not.
	 */
	public static ZoneOffset of(String configValue) {
		if (configValue == null) {
			return DEFAULT;
		}
		return DAYLIGHT_SAVING_VALUES.contains(LegacyValues.phpTrim(configValue).toLowerCase(Locale.ROOT))
				? DAYLIGHT_SAVING
				: DEFAULT;
	}

	/**
	 * The same offset in the {@code +HH:MM} form {@code SET time_zone} takes.
	 *
	 * <p>PHP builds the literal strings {@code '+03:00'} and {@code '+02:00'};
	 * {@link ZoneOffset#getId()} produces exactly those for whole-hour offsets.
	 */
	public static String sqlLiteral(ZoneOffset offset) {
		return offset.getId();
	}

	/**
	 * The same offset as the timezone <em>name</em>
	 * {@code date_default_timezone_get()} would return.
	 *
	 * <p>{@code applyRuntimeTimezoneFromConfigs()} does not set an offset, it
	 * sets a named zone: {@code date_default_timezone_set($offset === '+03:00'
	 * ? 'Etc/GMT-3' : 'Etc/GMT-2')} ({@code functions.php:257}). PHP then
	 * returns that exact string, so {@code configs/get.php}'s
	 * {@code server_timezone} is {@code "Etc/GMT-3"} or {@code "Etc/GMT-2"} and
	 * never {@code "+03:00"} or an IANA city zone.
	 *
	 * <p><b>The sign is inverted and that is not a typo.</b> POSIX-style
	 * {@code Etc/GMT-N} zones count <em>west</em>-positive, so {@code Etc/GMT-3}
	 * is UTC<b>+</b>3 and {@code Etc/GMT-2} is UTC<b>+</b>2 -- the same
	 * direction as {@link #DAYLIGHT_SAVING} and {@link #DEFAULT} despite
	 * reading like the opposite. Anyone "correcting" these to
	 * {@code Etc/GMT+3} would move every client's clock six hours.
	 *
	 * <p>The {@code ?: 'UTC'} fallback in the PHP is unreachable and is
	 * therefore not reproduced: {@code date_default_timezone_get()} always
	 * returns a non-empty string, and {@code applyRuntimeTimezoneFromConfigs()}
	 * has already run at include time in every request that can reach the
	 * endpoint.
	 */
	public static String zoneId(ZoneOffset offset) {
		return DAYLIGHT_SAVING.equals(offset) ? "Etc/GMT-3" : "Etc/GMT-2";
	}

}
