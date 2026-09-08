package com.workin.backend.platformadmin;

import java.util.List;
import java.util.regex.Pattern;

import com.workin.legacy.phone.LegacyPhoneNumbers;

/**
 * Validates a company submitted from the dashboard, reproducing
 * {@code company_admin_create()} and {@code company_admin_update()}
 * (<b>company_helper.php:234</b> and <b>:371</b>) rule for rule, in their
 * order.
 *
 * <p>The order is the interesting part and the reason this is its own class.
 * Legacy checks the country code before the names, the phone before the
 * password, and the password before the company name -- and each returns a
 * different message. Reordering them would still reject the same submissions
 * while telling the operator something else about why, which is a behaviour
 * change wearing a refactor's clothes.
 *
 * <p>What is <em>not</em> here is every rule that needs the database: the
 * phone's uniqueness, the three lookups existing, and a company code already
 * taken. Those interleave with these in the PHP, and
 * {@link PlatformAdminCompanyService} runs them in the same order rather than
 * this class reaching for a connection.
 */
public final class CompanyForm {

	/** {@code /^[A-Za-z0-9]{5,32}$/}. */
	private static final Pattern COMPANY_CODE = Pattern.compile("^[A-Za-z0-9]{5,32}$");

	/** {@code strlen($password) < 6}. */
	private static final int MIN_PASSWORD = 6;

	/**
	 * A validated submission. {@code password} is null when the operator left
	 * it blank on an edit, which legacy reads as "leave the stored one alone";
	 * {@code companyCode} is null when the form did not carry the field at all,
	 * which is legacy's {@code array_key_exists} rather than an empty string.
	 */
	public record CompanyWrite(
			String companyName, String firstName, String lastName,
			String countryCode, String phone, String password,
			String mainBranchAddress,
			long activityId, long titleId, long sizeId,
			String companyCode) {
	}

	/** @param errorKey a message key, or null when {@link #write} is present */
	public record Result(CompanyWrite write, String errorKey) {

		public boolean ok() {
			return this.write != null;
		}

		static Result rejected(String errorKey) {
			return new Result(null, errorKey);
		}
	}

	private CompanyForm() {
	}

	/**
	 * @param allowedCodes {@code array_keys(company_country_codes())} -- the
	 *        dial codes the phone_countries table carries, passed in so this
	 *        class stays a pure function of its arguments
	 * @param editing false for {@code company_admin_create}, true for
	 *        {@code company_admin_update} -- the two differ in exactly two
	 *        places: an edit tolerates a blank password, and it checks the
	 *        names and the company name together rather than in two steps.
	 */
	public static Result validate(LegacyPhoneNumbers phoneNumbers, List<String> allowedCodes,
			boolean editing,
			String companyName, String firstName, String lastName,
			String countryCode, String phoneLocal, String password,
			String mainBranchAddress, String activityId, String titleId, String sizeId,
			String companyCode) {

		String code = trim(countryCode).isEmpty() ? "+20" : trim(countryCode);
		if (!allowedCodes.isEmpty()
				&& !allowedCodes.contains(LegacyPhoneNumbers.normalizeDialCode(code))) {
			return Result.rejected("error_invalid_country");
		}

		String first = trim(firstName);
		String last = trim(lastName);
		String name = trim(companyName);
		String address = trim(mainBranchAddress);
		if (editing) {
			// update checks all four together; create checks the names first
			// and the company name after the password.
			if (first.isEmpty() || last.isEmpty() || name.isEmpty() || address.isEmpty()) {
				return Result.rejected("error_required");
			}
		}
		else if (first.isEmpty() || last.isEmpty()) {
			return Result.rejected("error_required");
		}

		String local = trim(phoneLocal);
		if (!phoneNumbers.isValidLocal(code, local)) {
			return Result.rejected("error_required");
		}
		// company_normalize_phone() is phone_digits_only() and nothing else --
		// the dial code is not prepended, so the stored value stays local.
		String phone = LegacyPhoneNumbers.digitsOnly(local);

		String secret = password == null ? "" : password;
		if (editing) {
			if (!secret.isEmpty() && secret.length() < MIN_PASSWORD) {
				return Result.rejected("error_password_min");
			}
		}
		else if (secret.length() < MIN_PASSWORD) {
			return Result.rejected("error_password_min");
		}

		if (!editing && (name.isEmpty() || address.isEmpty())) {
			return Result.rejected("error_required");
		}

		long activity = parseId(activityId);
		long title = parseId(titleId);
		long size = parseId(sizeId);
		if (activity <= 0 || title <= 0 || size <= 0) {
			return Result.rejected("error_required");
		}

		String upperCode = null;
		if (companyCode != null) {
			upperCode = trim(companyCode).toUpperCase(java.util.Locale.ROOT);
			if (upperCode.isEmpty()) {
				return Result.rejected("error_required");
			}
			if (!COMPANY_CODE.matcher(upperCode).matches()) {
				return Result.rejected("company_code_invalid");
			}
		}

		return new Result(new CompanyWrite(name, first, last,
				LegacyPhoneNumbers.normalizeDialCode(code), phone,
				secret.isEmpty() ? null : secret,
				address, activity, title, size, upperCode), null);
	}

	private static String trim(String value) {
		return value == null ? "" : value.trim();
	}

	private static long parseId(String raw) {
		try {
			return Long.parseLong(trim(raw));
		}
		catch (NumberFormatException notANumber) {
			// PHP's (int) cast on a non-numeric string is 0, which the caller
			// rejects as required.
			return 0L;
		}
	}
}
