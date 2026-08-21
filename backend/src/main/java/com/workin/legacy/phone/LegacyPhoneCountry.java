package com.workin.legacy.phone;

/**
 * One row of {@code phone_countries}, as the phone helpers read it.
 *
 * <p>Only the columns the normalization and validation path actually consults
 * are carried: {@code country_code}, {@code phone_length}, the raw
 * {@code phone_prefixes} text, and the ordering columns. {@code name_ar},
 * {@code name_en} and {@code flag_emoji} are read by the country-listing
 * endpoints ({@code phone_country_public_row()}, {@code phone_country_flag()}),
 * which belong to a later wave -- they are deliberately absent here rather than
 * carried unused.
 *
 * <p>{@code phone_prefixes} stays a raw string because
 * {@link LegacyPhoneNumbers#decodePrefixes} has to see exactly what the column
 * held: legacy accepts JSON and falls back to delimiter-splitting anything
 * else, and the schema's {@code json_valid} CHECK constrains new writes, not
 * rows that predate it.
 */
public record LegacyPhoneCountry(long id, String countryCode, int phoneLength, String phonePrefixes, int sortOrder) {
}
