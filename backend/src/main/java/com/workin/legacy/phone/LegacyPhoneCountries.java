package com.workin.legacy.phone;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * The storage half of legacy's phone-country helpers
 * ({@code helpers/phone_countries_helper.php:13-155}): the
 * {@code phone_countries} table, its existence probe, and the hard-coded
 * fallback definitions PHP uses when that table is not there.
 *
 * <p>Kept separate from {@link LegacyPhoneNumbers} on purpose -- normalization
 * and validation are pure functions over a country definition, and only this
 * class knows where a definition comes from.
 *
 * <h2>The table may be absent, and that is a supported state</h2>
 * <p>{@code phone_countries_table_exists()} probes
 * {@code information_schema.tables} and treats <em>any</em> throwable as "not
 * there" ({@code catch (Throwable $e) { $exists = false; }}), then falls back to
 * {@code phone_countries_fallback_rows()}. The vendored schema happens to
 * contain the table today, but a deployment that predates it must keep
 * accepting the same phone numbers, so the fallback is ported rather than
 * assumed dead.
 *
 * <h2>Request-scoped, because PHP's cache is</h2>
 * <p>{@code phone_countries_table_exists()} caches its answer in a
 * function-local {@code static}, which in PHP lives for <em>one request</em>
 * and is gone by the next one. A Spring singleton would turn a single transient
 * probe failure -- a dropped connection, a moment of lock contention -- into
 * fallback definitions for the rest of the JVM's life, while legacy would have
 * retried on the very next request. So this component is request-scoped and
 * keeps its cache per instance: probe once per request, reuse it within that
 * request, probe again on the next one. No global cache, no TTL, no refresh
 * schedule -- those would all be new behaviour, not the ported one.
 *
 * <h2>Not tenant-scoped</h2>
 * <p>{@code phone_countries} is reference configuration: no {@code company_id}
 * column exists in the schema and no query adds one. D-075 is about employee and
 * organization references; inventing a tenant scope here would reject numbers
 * legacy accepts.
 */
@Component
@RequestScope(proxyMode = ScopedProxyMode.TARGET_CLASS)
public class LegacyPhoneCountries {

	/**
	 * {@code phone_countries_fallback_rows()}
	 * ({@code phone_countries_helper.php:408-455}), verbatim: same four
	 * countries, same lengths, same prefixes, same order.
	 */
	private static final List<LegacyPhoneCountry> FALLBACK = List.of(
			new LegacyPhoneCountry(1, "+20", 11, "[\"010\",\"011\",\"012\",\"015\"]", 1),
			new LegacyPhoneCountry(2, "+966", 10, "[\"05\"]", 2),
			new LegacyPhoneCountry(3, "+971", 10, "[\"050\",\"052\",\"054\",\"055\",\"056\",\"058\"]", 3),
			new LegacyPhoneCountry(4, "+218", 10, "[\"091\",\"092\",\"093\",\"094\",\"095\",\"096\"]", 4));

	private final JdbcTemplate jdbcTemplate;

	/** PHP's {@code static $exists}: probed once per request, then reused within it. */
	private Boolean tableExists;

	public LegacyPhoneCountries(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/**
	 * {@code phone_countries_all_active()}: active rows ordered by
	 * {@code sort_order ASC, id ASC}, or the fallback list when the table is
	 * absent. The order is load-bearing -- {@code phone_country_resolve_code()}
	 * and {@code phone_country_default_code()} both take the first entry as the
	 * default country.
	 */
	public List<LegacyPhoneCountry> allActive() {
		if (!tableExists()) {
			return FALLBACK;
		}
		return jdbcTemplate.query(
				"""
				SELECT id, country_code, phone_length, phone_prefixes, sort_order
				FROM phone_countries
				WHERE is_active = 1
				ORDER BY sort_order ASC, id ASC""",
				(rs, rowNumber) -> new LegacyPhoneCountry(
						rs.getLong("id"), rs.getString("country_code"), rs.getInt("phone_length"),
						rs.getString("phone_prefixes"), rs.getInt("sort_order")));
	}

	/**
	 * {@code phone_country_find($country_code)}: an <em>active</em> row for the
	 * exact dial code. With the table absent, PHP scans the fallback rows for an
	 * exact {@code country_code} match instead.
	 */
	public Optional<LegacyPhoneCountry> find(String countryCode) {
		String code = countryCode == null ? "" : countryCode.trim();
		if (code.isEmpty()) {
			return Optional.empty();
		}
		if (!tableExists()) {
			return FALLBACK.stream().filter(row -> row.countryCode().equals(code)).findFirst();
		}
		List<LegacyPhoneCountry> rows = jdbcTemplate.query(
				"""
				SELECT id, country_code, phone_length, phone_prefixes, sort_order
				FROM phone_countries
				WHERE country_code = ? AND is_active = 1
				LIMIT 1""",
				(rs, rowNumber) -> new LegacyPhoneCountry(
						rs.getLong("id"), rs.getString("country_code"), rs.getInt("phone_length"),
						rs.getString("phone_prefixes"), rs.getInt("sort_order")),
				code);
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
	}

	/**
	 * {@code phone_countries_dial_codes()} -- the keys of
	 * {@code phone_countries_map_by_code()}, so blank codes are skipped and a
	 * duplicated code keeps its <em>last</em> row's position, which is what
	 * PHP's map assignment does.
	 */
	public List<String> dialCodes() {
		Map<String, LegacyPhoneCountry> byCode = new LinkedHashMap<>();
		for (LegacyPhoneCountry row : allActive()) {
			String code = row.countryCode() == null ? "" : row.countryCode().trim();
			if (!code.isEmpty()) {
				byCode.put(code, row);
			}
		}
		return new ArrayList<>(byCode.keySet());
	}

	/** {@code phone_country_default_code()}: the first active dial code, else {@code +20}. */
	public String defaultCode() {
		List<String> codes = dialCodes();
		return codes.isEmpty() ? "+20" : codes.get(0);
	}

	/**
	 * {@code phone_countries_table_exists()}. Any failure means "absent": PHP
	 * wraps the probe in {@code catch (Throwable $e)}, so a permissions error, a
	 * broken connection or anything else sends it to the fallback definitions
	 * rather than failing the request.
	 *
	 * <p>{@code Throwable} is deliberate and is scoped to this one statement --
	 * ordinary country reads, employee logic and the endpoint itself are all
	 * outside it, so a real failure there still surfaces.
	 */
	boolean tableExists() {
		Boolean cached = tableExists;
		if (cached != null) {
			return cached;
		}
		boolean exists;
		try {
			Long count = jdbcTemplate.queryForObject(
					"""
					SELECT COUNT(*) FROM information_schema.tables
					WHERE table_schema = DATABASE() AND table_name = ?""",
					Long.class, "phone_countries");
			exists = count != null && count > 0;
		} catch (Throwable ex) { // NOPMD - catch (Throwable $e), narrowly, as PHP does
			exists = false;
		}
		tableExists = exists;
		return exists;
	}

	/** The fallback definitions, exposed for the tests that prove them. */
	static List<LegacyPhoneCountry> fallbackRows() {
		return FALLBACK;
	}

}
