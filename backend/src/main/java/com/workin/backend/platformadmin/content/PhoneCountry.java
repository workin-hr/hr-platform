package com.workin.backend.platformadmin.content;

import java.util.List;

/**
 * A row of {@code phone_countries} -- the dial codes and per-country phone
 * rules the mobile and desktop clients read through
 * {@code phone_countries/list}.
 *
 * <p>The clients only ever read them. Before ADR-0016 the write side
 * existed solely in the PHP dashboard, so switching PHP off would have
 * frozen this table permanently (R-023's neighbour in that ADR's table).
 *
 * @param prefixes the valid local-number prefixes, stored in MySQL as a JSON
 *                 array in {@code phone_prefixes} and carried here as a list
 *                 so no caller has to know that
 */
public record PhoneCountry(
		long id,
		String countryCode,
		String nameAr,
		String nameEn,
		String flagEmoji,
		int phoneLength,
		List<String> prefixes,
		boolean active,
		int sortOrder) {
}
