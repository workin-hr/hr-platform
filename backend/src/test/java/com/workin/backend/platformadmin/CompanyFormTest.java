package com.workin.backend.platformadmin;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.AbstractDataSource;

import org.junit.jupiter.api.Test;

import com.workin.legacy.phone.LegacyPhoneCountries;
import com.workin.legacy.phone.LegacyPhoneNumbers;

/**
 * {@link CompanyForm} against {@code company_admin_create()} and
 * {@code company_admin_update()} (company_helper.php:234 and :371).
 *
 * <p>The assertions are mostly about <b>which</b> message comes back, not
 * whether one does. Every rule here rejects, so a test that only checked
 * rejection would pass with the rules in any order -- and the order is the
 * thing that decides what the operator is told. Legacy checks the country code
 * before the names, the phone before the password, and the password before the
 * company name; a submission that breaks two rules must name the first.
 */
class CompanyFormTest {

	private static final List<String> CODES = List.of("+20", "+966");

	/**
	 * Never queried. {@code isValidLocal("+20", ...)} is a regex on digits that
	 * {@code normalizeLocal} produced, and it returns before the countries
	 * table is consulted -- so this throws rather than returning a connection,
	 * and the test fails loudly if that ever stops being true.
	 */
	private static final DataSource UNUSED = new AbstractDataSource() {
		@Override
		public Connection getConnection() {
			throw new UnsupportedOperationException("CompanyFormTest must not reach a database");
		}

		@Override
		public Connection getConnection(String username, String password) {
			return getConnection();
		}
	};

	private final LegacyPhoneNumbers phoneNumbers =
			new LegacyPhoneNumbers(new LegacyPhoneCountries(UNUSED));

	private CompanyForm.Result create(String countryCode, String first, String last,
			String phone, String password, String name, String address) {
		return CompanyForm.validate(this.phoneNumbers, CODES, false,
				name, first, last, countryCode, phone, password, address,
				"1", "1", "1", null);
	}

	@Test
	void aCodeOutsideThePhoneCountriesTableIsRefusedBeforeAnythingElse() {
		// Everything else is blank too; the country code still wins.
		assertThat(create("+999", "", "", "", "", "", "").errorKey())
				.isEqualTo("error_invalid_country");
	}

	@Test
	void theNamesAreCheckedBeforeThePhone() {
		assertThat(create("+20", "", "", "not-a-phone", "short", "", "").errorKey())
				.isEqualTo("error_required");
	}

	@Test
	void thePasswordIsCheckedBeforeTheCompanyName() {
		// A valid Egyptian mobile, five characters of password, no company
		// name: legacy answers about the password.
		assertThat(create("+20", "Karim", "Taha", "01000000002", "12345", "", "").errorKey())
				.isEqualTo("error_password_min");
	}

	@Test
	void theCompanyNameAndAddressAreRequiredOnceThePasswordPasses() {
		assertThat(create("+20", "Karim", "Taha", "01000000002", "123456", "", "").errorKey())
				.isEqualTo("error_required");
	}

	@Test
	void aCompleteSubmissionIsAcceptedAndKeepsThePhoneLocal() {
		CompanyForm.Result result = create(
				"+20", "Karim", "Taha", "01000000002", "123456", "Workin", "Cairo");
		assertThat(result.ok()).isTrue();
		// company_normalize_phone() is digits only -- the dial code is not
		// prepended, so the stored value stays as the owner types it.
		assertThat(result.write().phone()).isEqualTo("01000000002");
		assertThat(result.write().countryCode()).isEqualTo("+20");
		assertThat(result.write().password()).isEqualTo("123456");
	}

	@Test
	void anEditToleratesABlankPasswordAndReportsItAsUnchanged() {
		CompanyForm.Result result = CompanyForm.validate(this.phoneNumbers, CODES, true,
				"Workin", "Karim", "Taha", "+20", "01000000002", "", "Cairo",
				"1", "1", "1", null);
		assertThat(result.ok()).isTrue();
		assertThat(result.write().password())
				.as("null is what tells the service to leave the stored hash alone")
				.isNull();
	}

	@Test
	void anEditStillRefusesAPasswordThatIsPresentAndTooShort() {
		assertThat(CompanyForm.validate(this.phoneNumbers, CODES, true,
				"Workin", "Karim", "Taha", "+20", "01000000002", "12345", "Cairo",
				"1", "1", "1", null).errorKey())
				.isEqualTo("error_password_min");
	}

	@Test
	void aCompanyCodeIsUppercasedAndMustMatchLegacysPattern() {
		assertThat(CompanyForm.validate(this.phoneNumbers, CODES, true,
				"Workin", "Karim", "Taha", "+20", "01000000002", "", "Cairo",
				"1", "1", "1", "ab-cd").errorKey())
				.isEqualTo("company_code_invalid");

		assertThat(CompanyForm.validate(this.phoneNumbers, CODES, true,
				"Workin", "Karim", "Taha", "+20", "01000000002", "", "Cairo",
				"1", "1", "1", "workin01").write().companyCode())
				.isEqualTo("WORKIN01");
	}

	@Test
	void anAbsentCompanyCodeStaysNullRatherThanBecomingEmpty() {
		// array_key_exists() in the PHP: a form that does not carry the field
		// leaves the column alone, which is not the same as clearing it.
		assertThat(CompanyForm.validate(this.phoneNumbers, CODES, true,
				"Workin", "Karim", "Taha", "+20", "01000000002", "", "Cairo",
				"1", "1", "1", null).write().companyCode())
				.isNull();
	}

	@Test
	void aLookupIdThatIsNotANumberIsZeroAndSoIsRequired() {
		assertThat(CompanyForm.validate(this.phoneNumbers, CODES, false,
				"Workin", "Karim", "Taha", "+20", "01000000002", "123456", "Cairo",
				"not-a-number", "1", "1", null).errorKey())
				.isEqualTo("error_required");
	}
}
