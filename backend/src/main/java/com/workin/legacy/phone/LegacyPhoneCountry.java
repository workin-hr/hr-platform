package com.workin.legacy.phone;

/**
 * One row of {@code phone_countries}, as the phone helpers read it.
 *
 * <p>Carries the columns the normalization and validation path consults --
 * {@code country_code}, {@code phone_length}, the raw {@code phone_prefixes}
 * text and the ordering columns -- plus the display columns
 * {@code phone_country_public_row()} shapes into a response
 * ({@code phone_countries_helper.php:282-299}). The display columns were
 * deliberately absent until an endpoint read them; Item 13.5 delivers
 * {@code phone_countries/list.php}, which does.
 *
 * <p>{@code phone_prefixes} stays a raw string because
 * {@link LegacyPhoneNumbers#decodePrefixes} has to see exactly what the column
 * held: legacy accepts JSON and falls back to delimiter-splitting anything
 * else, and the schema's {@code json_valid} CHECK constrains new writes, not
 * rows that predate it.
 */
public record LegacyPhoneCountry(
		long id, String countryCode, String nameAr, String nameEn, String flagEmoji,
		int phoneLength, String phonePrefixes, int sortOrder, int isActive) {
}
