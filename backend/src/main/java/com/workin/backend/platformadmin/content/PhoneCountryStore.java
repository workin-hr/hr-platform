package com.workin.backend.platformadmin.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code phone_countries} in the legacy MySQL schema.
 *
 * <p>{@code @Profile("phase1-mysql")}: the table is part of the legacy
 * contract and has no PostgreSQL counterpart, so under the other profile
 * this bean does not exist and {@link
 * com.workin.backend.platformadmin.web.AdminPageAvailability} reports the
 * page as unavailable rather than the sidebar offering a link that fails.
 *
 * <p>Plain {@code JdbcTemplate} rather than an entity, matching the legacy
 * adapter's own approach: the row shape is the PHP dashboard's contract,
 * not a domain model this application owns, and mapping it through JPA
 * would invite someone to "improve" a column the clients read.
 */
@Repository
@Profile("phase1-mysql")
public class PhoneCountryStore {

	private static final ObjectMapper JSON = new ObjectMapper();

	/** {@code dashboard_phone_countries_list()}: active first is <em>not</em> the order -- sort_order then id is. */
	private static final String ORDER = " ORDER BY sort_order ASC, id ASC";

	private final JdbcTemplate jdbcTemplate;

	public PhoneCountryStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	private final RowMapper<PhoneCountry> mapper = (rs, rowNum) -> new PhoneCountry(
			rs.getLong("id"),
			rs.getString("country_code"),
			rs.getString("name_ar"),
			rs.getString("name_en"),
			rs.getString("flag_emoji"),
			rs.getInt("phone_length"),
			decodePrefixes(rs.getString("phone_prefixes")),
			rs.getInt("is_active") == 1,
			rs.getInt("sort_order"));

	public List<PhoneCountry> list() {
		return this.jdbcTemplate.query("SELECT * FROM phone_countries" + ORDER, this.mapper);
	}

	public Optional<PhoneCountry> find(long id) {
		return this.jdbcTemplate.query("SELECT * FROM phone_countries WHERE id = ?", this.mapper, id)
				.stream().findFirst();
	}

	/**
	 * @param excludeId the row being edited, so its own code does not read as a clash
	 * @return true when another row already claims {@code countryCode}
	 */
	public boolean countryCodeTaken(String countryCode, Long excludeId) {
		String sql = "SELECT COUNT(*) FROM phone_countries WHERE country_code = ?"
				+ (excludeId == null ? "" : " AND id <> ?");
		Integer count = excludeId == null
				? this.jdbcTemplate.queryForObject(sql, Integer.class, countryCode)
				: this.jdbcTemplate.queryForObject(sql, Integer.class, countryCode, excludeId);
		return count != null && count > 0;
	}

	public void insert(PhoneCountry country) {
		this.jdbcTemplate.update("""
				INSERT INTO phone_countries
				  (country_code, name_ar, name_en, flag_emoji, phone_length, phone_prefixes, is_active, sort_order)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
				country.countryCode(), country.nameAr(), country.nameEn(), country.flagEmoji(),
				country.phoneLength(), encodePrefixes(country.prefixes()),
				country.active() ? 1 : 0, country.sortOrder());
	}

	public void update(long id, PhoneCountry country) {
		this.jdbcTemplate.update("""
				UPDATE phone_countries
				   SET country_code = ?, name_ar = ?, name_en = ?, flag_emoji = ?,
				       phone_length = ?, phone_prefixes = ?, is_active = ?, sort_order = ?
				 WHERE id = ?""",
				country.countryCode(), country.nameAr(), country.nameEn(), country.flagEmoji(),
				country.phoneLength(), encodePrefixes(country.prefixes()),
				country.active() ? 1 : 0, country.sortOrder(), id);
	}

	public void delete(long id) {
		this.jdbcTemplate.update("DELETE FROM phone_countries WHERE id = ?", id);
	}

	/**
	 * {@code phone_country_decode_prefixes()}. The column is a JSON array,
	 * but legacy rows predate that and can hold a bare comma-separated
	 * string, so both are accepted -- a row the dashboard wrote years ago
	 * must still render.
	 */
	static List<String> decodePrefixes(String raw) {
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		String trimmed = raw.trim();
		if (trimmed.startsWith("[")) {
			try {
				return JSON.readValue(trimmed, JSON.getTypeFactory()
						.constructCollectionType(List.class, String.class));
			} catch (JacksonException ex) {
				return List.of();
			}
		}
		List<String> parsed = new ArrayList<>();
		for (String part : trimmed.split(",")) {
			String value = part.trim();
			if (!value.isEmpty()) {
				parsed.add(value);
			}
		}
		return List.copyOf(parsed);
	}

	/** Always JSON on the way out, matching {@code json_encode(..., JSON_UNESCAPED_UNICODE)}. */
	static String encodePrefixes(List<String> prefixes) {
		try {
			return JSON.writeValueAsString(prefixes == null ? List.of() : prefixes);
		} catch (JacksonException ex) {
			return "[]";
		}
	}

}
