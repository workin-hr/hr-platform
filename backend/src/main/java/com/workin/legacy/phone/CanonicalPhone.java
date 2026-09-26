package com.workin.legacy.phone;

import java.util.LinkedHashSet;
import java.util.List;

import com.google.i18n.phonenumbers.PhoneNumberUtil;

/**
 * One phone number as {@link CanonicalPhones} identified it: the E.164 form
 * that <em>is</em> its identity, and the parts the storage convention and the
 * lookups are derived from (ADR-0020).
 *
 * @param e164 the identity, e.g. {@code +201012345678}
 * @param countryCallingCode e.g. {@code 20}
 * @param region the ISO region the number belongs to, e.g. {@code EG}
 * @param nationalSignificantNumber e.g. {@code 1012345678}
 * @param nationalDigits the digits of the region's national format, with its
 *        trunk prefix, e.g. {@code 01012345678} -- what every Java write
 *        stores in {@code phone}, because it is what PHP and the clients have
 *        always stored and shown
 * @param mobile whether the metadata types it as a mobile number (or as one it
 *        cannot tell from a fixed line)
 */
public record CanonicalPhone(
		String e164, int countryCallingCode, String region, String nationalSignificantNumber,
		String nationalDigits, boolean mobile) {

	/** The value stored in {@code country_code} beside {@link #nationalDigits}: {@code +20}. */
	public String dialCode() {
		return "+" + this.countryCallingCode;
	}

	/**
	 * Every spelling of this number a {@code phone} column may already hold
	 * as bare digits: the national form, the national form with the metadata's
	 * trunk prefix, the significant number alone, and the three international
	 * forms. Bounded and derived from the metadata -- nothing per country is
	 * written here -- so a lookup can bind these instead of the request's
	 * input and still use the column's unique index.
	 */
	public List<String> storedSpellings() {
		LinkedHashSet<String> spellings = new LinkedHashSet<>();
		spellings.add(this.nationalDigits);
		String trunkPrefix = PhoneNumberUtil.getInstance().getNddPrefixForRegion(this.region, true);
		if (trunkPrefix != null && !trunkPrefix.isEmpty()) {
			spellings.add(trunkPrefix + this.nationalSignificantNumber);
		}
		spellings.add(this.nationalSignificantNumber);
		String international = this.countryCallingCode + this.nationalSignificantNumber;
		spellings.add(international);
		spellings.add("+" + international);
		spellings.add("00" + international);
		return List.copyOf(spellings);
	}
}
