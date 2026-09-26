package com.workin.backend.platformadmin.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.workin.legacy.phone.LegacyPhoneCountry;
import com.workin.legacy.phone.LegacyPhoneNumbers;

/**
 * What a company or employee form needs from {@code phone_countries}: the country
 * select's options and each country's length and prefixes, published for display
 * only. Legacy's {@code phone-validator.js} judged a number by them; since D-291 the
 * browser enforces none of it and the server's libphonenumber check is the one
 * authority (ADR-0020).
 *
 * <p>{@link #options()} are {@code phone_country_codes_for_select()}, each labelled
 * by {@code phone_country_option_label()} ({@code phone_countries_helper.php:375-397}):
 * flag, localized name, then the code in brackets. {@link #rules()} is
 * {@code phone_countries_validation_rules_for_js()} ({@code :358-373}) as JSON, the
 * value legacy's layout assigns to {@code window.WorkinPhoneCountriesRules}. Both come
 * from one read of the active rows, the table that decides which countries are
 * offered.
 *
 * <p>{@link #NONE} where the legacy database is not there to read. Its rules are
 * null, and the layout loads no phone script without rules.
 */
public record PhoneCountryChoices(String rules, List<Option> options) {

	public static final PhoneCountryChoices NONE = new PhoneCountryChoices(null, List.of());

	private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

	/** One {@code <option>}: the code it posts and the label it shows. */
	public record Option(String code, String label) {
	}

	/** Whether the select lists this code, which a stored row may hold after its country is retired. */
	public boolean offers(String code) {
		return this.options.stream().anyMatch(option -> option.code().equals(code));
	}

	/**
	 * @param active {@code phone_countries_all_active()}, in its order
	 * @param lang the dashboard language; English names only for {@code en}
	 */
	static PhoneCountryChoices of(List<LegacyPhoneCountry> active, String lang) {
		Map<String, Map<String, Object>> rules = new LinkedHashMap<>();
		List<Option> options = new ArrayList<>();
		for (LegacyPhoneCountry country : active) {
			String code = country.countryCode() == null ? "" : country.countryCode();
			if (!code.trim().isEmpty()) {
				Map<String, Object> rule = new LinkedHashMap<>();
				rule.put("phone_length", country.phoneLength());
				rule.put("phone_prefixes", LegacyPhoneNumbers.decodePrefixes(country.phonePrefixes()));
				rules.put(code.trim(), rule);
			}
			if (!code.isEmpty()) {
				options.add(new Option(code, label(country, code, lang)));
			}
		}
		return new PhoneCountryChoices(JSON.writeValueAsString(rules), List.copyOf(options));
	}

	/**
	 * {@code phone_country_option_label()}. The name is {@code phone_country_localized_name()}:
	 * the language's own name, or the Arabic one when that is blank.
	 */
	private static String label(LegacyPhoneCountry country, String code, String lang) {
		String name = trim(lang != null && lang.trim().toLowerCase(java.util.Locale.ROOT).startsWith("en")
				? country.nameEn() : country.nameAr());
		if (name.isEmpty()) {
			name = trim(country.nameAr());
		}
		return (trim(country.flagEmoji()) + " " + name + " (" + code + ")").trim();
	}

	private static String trim(String value) {
		return value == null ? "" : value.trim();
	}
}
