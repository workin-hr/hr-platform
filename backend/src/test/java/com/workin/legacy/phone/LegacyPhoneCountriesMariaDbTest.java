package com.workin.legacy.phone;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.workin.legacy.AbstractLegacyMySqlTest;
import com.workin.legacy.employees.LegacyEmployeeStore;

/**
 * The storage-dependent half of the phone port, against real MariaDB and the
 * real {@code phone_countries} table: which definition wins, what happens when
 * the table is not there at all, and the global phone-uniqueness query
 * {@code employees/create.php} gates on.
 *
 * <p>Runs on the shared legacy container (ids in the 197xxx range are this
 * class's) rather than starting its own, because none of it needs an HTTP
 * server.
 */
class LegacyPhoneCountriesMariaDbTest extends AbstractLegacyMySqlTest {

	private static final long COMPANY = 19701L;
	private static final long BRANCH = 19711L;
	private static final long ACCEPTED = 197011L;
	private static final long LEGACY_DEFAULT_STATUS = 197012L;
	private static final long REJECTED = 197013L;
	private static final long FORMATTED = 197014L;
	private static final long WITHOUT_TRUNK_ZERO = 197015L;
	private static final long SHARED_DIGITS = 197016L;
	private static final long REJECTED_INTERNATIONAL = 197017L;

	private static DataSource dataSource;
	private static LegacyPhoneCountries countries;
	private static LegacyPhoneNumbers numbers;
	private static LegacyEmployeeStore employees;

	@BeforeAll
	static void prepare() throws Exception {
		dataSource = dataSourceFor(MARIADB.getDatabaseName());
		countries = new LegacyPhoneCountries(dataSource);
		numbers = new LegacyPhoneNumbers(countries);
		employees = new LegacyEmployeeStore(dataSource);
		seed();
	}

	@Test
	void theTableIsUsedWhenItExistsAndOnlyActiveRowsCount() {
		assertThat(countries.tableExists()).isTrue();

		List<String> codes = countries.dialCodes();
		// Seeded active rows, ordered by sort_order then id.
		assertThat(codes).containsExactly("+20", "+218", "+973");
		// is_active = 0 is invisible to every read path.
		assertThat(codes).doesNotContain("+964");
		assertThat(countries.find("+964")).isEmpty();
		assertThat(countries.find("+218")).isPresent();
	}

	@Test
	void theFirstActiveRowIsTheDefaultCountry() {
		// phone_country_default_code() and phone_country_resolve_code() both
		// take codes[0] -- the sort_order/id ordering is what decides it.
		assertThat(countries.defaultCode()).isEqualTo("+20");
		assertThat(numbers.resolveCode("+218")).isEqualTo("+218");
		assertThat(numbers.resolveCode("218")).isEqualTo("+218");
		assertThat(numbers.resolveCode("020")).isEqualTo("+20");
		// Unknown and empty codes both fall back to the first configured code.
		assertThat(numbers.resolveCode("+999")).isEqualTo("+20");
		assertThat(numbers.resolveCode("")).isEqualTo("+20");
		// An inactive definition is not a known code.
		assertThat(numbers.resolveCode("+964")).isEqualTo("+20");
	}

	@Test
	void anOfferedCountryIsValidatedByTheMetadataNotByItsRow() {
		// +218 is seeded with length 10 and JSON prefixes 091..096. The row
		// offers the country; libphonenumber decides the numbers (ADR-0020).
		assertThat(numbers.forAccount("0912345678", "+218").map(CanonicalPhone::e164)).contains("+218912345678");
		assertThat(numbers.forAccount("0962345678", "+218")).isPresent();
		// A missing trunk zero is the metadata's to restore now.
		assertThat(numbers.forAccount("912345678", "+218").map(CanonicalPhone::nationalDigits)).contains("0912345678");
		// Wrong length.
		assertThat(numbers.forAccount("091234567", "+218")).isEmpty();
		assertThat(numbers.forAccount("09123456789", "+218")).isEmpty();
		// 081 is a Libyan landline -- a number, but not one an account may hold.
		assertThat(numbers.forAccount("0812345678", "+218")).isEmpty();
	}

	@Test
	void aRowsPrefixListNoLongerDecidesValidity() {
		// +973 is seeded with "033 034;035" rather than JSON, which json_decode
		// rejects and PHP splits on [\s,;]+. The list still decodes for the
		// selectors that display it, and no longer decides which numbers pass:
		// 36 is a Bahraini mobile range the row never listed.
		assertThat(LegacyPhoneNumbers.decodePrefixes(countries.find("+973").orElseThrow().phonePrefixes()))
				.containsExactly("033", "034", "035");
		assertThat(numbers.forAccount("36123456", "+973").map(CanonicalPhone::e164)).contains("+97336123456");
		assertThat(numbers.forAccount("03312345", "+973")).isEmpty();
	}

	@Test
	void egyptIgnoresTheTable() {
		// The seeded +20 row carries a deliberately wrong length and prefix
		// set, and Egyptian numbers still behave -- PHP special-cased +20 and
		// the metadata does not read the row at all.
		assertThat(numbers.forAccount("01012345678", "+20")).isPresent();
		assertThat(numbers.forAccount("01512345678", "+20")).isPresent();
		assertThat(numbers.forAccount("01312345678", "+20")).isEmpty();
		assertThat(numbers.forAccount("0101234567", "+20")).isEmpty();
		for (String spelling : List.of("1012345678", "201012345678", "+20 10 1234 5678")) {
			assertThat(numbers.forAccount(spelling, "+20").map(CanonicalPhone::nationalDigits))
					.as(spelling).contains("01012345678");
		}
		assertThat(numbers.forAccount("201012345678", "20").map(CanonicalPhone::nationalDigits))
				.contains("01012345678");
	}

	@Test
	void aCountryOutsideTheTableIsOfferedOnlyWhenPhpsFallbackRulesAcceptedIt() {
		// +966 has no row here, and phone_is_valid_local_legacy() still took it.
		assertThat(countries.find("+966")).isEmpty();
		assertThat(numbers.forAccount("0512345678", "+966")).isPresent();
		assertThat(numbers.forAccount("0412345678", "+966")).isEmpty();
		// An inactive row offers nothing, and neither does a country no rule
		// ever accepted -- however valid the number is where it belongs.
		assertThat(numbers.forAccount("07712345678", "+964")).isEmpty();
		assertThat(numbers.forAccount("07400123456", "+44")).isEmpty();
		assertThat(numbers.forAccount("12345678", "+999")).isEmpty();
		assertThat(numbers.offeredDialCodes()).containsExactly("+20", "+966", "+971", "+218", "+973");
	}

	@Test
	void anAbsentTableFallsBackToPhpsBuiltInDefinitions() throws Exception {
		// The probe runs against a real information_schema in a database that
		// genuinely has no phone_countries table -- not a stubbed flag.
		// The application user cannot create schemas, so this runs as root --
		// the point is a real information_schema lookup against a database that
		// genuinely lacks the table, which a stubbed flag could not prove.
		try (Connection connection = DriverManager.getConnection(
						MARIADB.getJdbcUrl(), "root", MARIADB.getPassword());
				Statement st = connection.createStatement()) {
			st.execute("CREATE DATABASE IF NOT EXISTS legacy_without_phone_countries");
			st.execute("GRANT ALL ON legacy_without_phone_countries.* TO '"
					+ MARIADB.getUsername() + "'@'%'");
			st.execute("FLUSH PRIVILEGES");
		}
		LegacyPhoneCountries absent = new LegacyPhoneCountries(
				dataSourceFor("legacy_without_phone_countries"));
		LegacyPhoneNumbers fallbackNumbers = new LegacyPhoneNumbers(absent);

		assertThat(absent.tableExists()).isFalse();
		assertThat(absent.dialCodes()).containsExactly("+20", "+966", "+971", "+218");
		assertThat(absent.defaultCode()).isEqualTo("+20");
		assertThat(absent.find("+966")).isPresent();
		assertThat(absent.find("+973")).isEmpty();

		// The fallback rows are the countries then offered; the metadata
		// validates inside them.
		assertThat(fallbackNumbers.offeredDialCodes()).containsExactly("+20", "+966", "+971", "+218");
		assertThat(fallbackNumbers.forAccount("0512345678", "+966")).isPresent();
		assertThat(fallbackNumbers.forAccount("051234567", "+966")).isEmpty();
		assertThat(fallbackNumbers.forAccount("0501234567", "+971")).isPresent();
		assertThat(fallbackNumbers.forAccount("0511234567", "+971")).isEmpty();
		assertThat(fallbackNumbers.forAccount("0912345678", "+218")).isPresent();
		assertThat(fallbackNumbers.forAccount("36123456", "+973")).isEmpty();
	}

	@Test
	void aLaterInstanceRecoversAfterAFailedProbe() throws Exception {
		// The request-scope property against a real database: one request's
		// probe fails and gets the fallback definitions; the next request's
		// instance probes again and finds the real table. A JVM-wide cache
		// would have left every later request on the fallback.
		RecoveringDataSource flaky = new RecoveringDataSource(dataSource);

		LegacyPhoneCountries duringOutage = new LegacyPhoneCountries(flaky);
		assertThat(duringOutage.tableExists()).isFalse();
		// +973 exists only in the table, never in the fallback definitions.
		assertThat(duringOutage.find("+973")).isEmpty();
		assertThat(duringOutage.find("+966")).isPresent();

		LegacyPhoneCountries afterRecovery = new LegacyPhoneCountries(flaky);
		assertThat(afterRecovery.tableExists()).isTrue();
		assertThat(afterRecovery.find("+973")).isPresent();
		assertThat(afterRecovery.find("+966")).isEmpty();
	}

	@Test
	void globalPhoneUniquenessMatchesTheCanonicalNumberInEveryStoredSpelling() {
		// The stored number, whichever way the new one was written.
		for (String spelling : List.of("01012345678", "1012345678", "201012345678", "+20 (10) 1234-5678")) {
			assertThat(employees.phoneExistsGlobally(egyptian(spelling), null)).as(spelling).isTrue();
		}
		// A row stored without its trunk zero is found by the national form.
		assertThat(employees.phoneExistsGlobally(egyptian("01066666666"), null)).isTrue();
		// An unrelated number is free.
		assertThat(employees.phoneExistsGlobally(egyptian("01111111111"), null)).isFalse();
		// The same digits in another country are another number: the row is an
		// Egyptian (Mansoura) number. A Saudi mobile spelled alike is still
		// refused, because a write stores it as 0502345678 -- the row's exact
		// digits, which the column's unique index refuses (round 5 of #360).
		assertThat(employees.phoneExistsGlobally(egyptian("0502345678"), null)).isTrue();
		assertThat(employees.phoneExistsGlobally(
				CanonicalPhones.parse("0502345678", "+966").orElseThrow(), null)).isTrue();
		// A Saudi number whose written digits no row holds is free, even where
		// an Egyptian row holds its digits in another spelling.
		assertThat(employees.phoneExistsGlobally(
				CanonicalPhones.parse("0502345677", "+966").orElseThrow(), null)).isFalse();
	}

	@Test
	void aStoredValueWithFormattingInTheColumnIsNotACandidate() {
		// PHP matched REPLACE()-stripped column values; the lookup binds the
		// number's digit spellings and uses the unique index instead, so a
		// value stored with punctuation is not found. The owner's production
		// profile (2026-09-26) has no such value in either table, and every
		// Java write stores digits (ADR-0020 records the re-check).
		assertThat(employees.phoneExistsGlobally(egyptian("01099999999"), null)).isFalse();
	}

	@Test
	void globalPhoneUniquenessIsGlobalButSkipsRejectedJoinRequests() {
		// The row is in another company entirely -- there is no company_id
		// predicate, because employees.phone is globally unique.
		assertThat(employees.phoneExistsGlobally(egyptian("01055555555"), null)).isTrue();
		// The schema default ('accepted', written by omitting the column) counts.
		assertThat(employees.phoneExistsGlobally(egyptian("01044444444"), null)).isTrue();
		// A rejected join request does not reserve the number -- but a row
		// holding exactly the digits a write would store blocks the unique
		// index whatever its status, so the national spelling is refused and
		// only a rejected row stored in another spelling leaves it free.
		assertThat(employees.phoneExistsGlobally(egyptian("01033333333"), null)).isTrue();
		assertThat(employees.phoneExistsGlobally(egyptian("01022222222"), null)).isFalse();
		// The exclusion is only applied for a positive id.
		assertThat(employees.phoneExistsGlobally(egyptian("01012345678"), ACCEPTED)).isFalse();
		assertThat(employees.phoneExistsGlobally(egyptian("01012345678"), 0L)).isTrue();
		assertThat(employees.phoneExistsGlobally(egyptian("01012345678"), -5L)).isTrue();
	}

	private static CanonicalPhone egyptian(String spelling) {
		return CanonicalPhones.parse(spelling, null).orElseThrow();
	}

	/** Fails the first connection attempt, then delegates -- one bad moment, then health. */
	private static final class RecoveringDataSource extends org.springframework.jdbc.datasource.AbstractDataSource {

		private final DataSource delegate;
		private final java.util.concurrent.atomic.AtomicBoolean failed =
				new java.util.concurrent.atomic.AtomicBoolean();

		private RecoveringDataSource(DataSource delegate) {
			this.delegate = delegate;
		}

		@Override
		public java.sql.Connection getConnection() throws java.sql.SQLException {
			if (failed.compareAndSet(false, true)) {
				throw new java.sql.SQLException("transient connection failure");
			}
			return delegate.getConnection();
		}

		@Override
		public java.sql.Connection getConnection(String username, String password) throws java.sql.SQLException {
			return getConnection();
		}

	}

	private static DataSource dataSourceFor(String database) {
		String url = MARIADB.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
		DriverManagerDataSource source = new DriverManagerDataSource(url, MARIADB.getUsername(), MARIADB.getPassword());
		source.setDriverClassName("org.mariadb.jdbc.Driver");
		return source;
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			// A deliberately wrong +20 definition: Egypt must not depend on it.
			st.execute("""
					INSERT INTO phone_countries
					  (id, country_code, name_ar, name_en, phone_length, phone_prefixes, is_active, sort_order)
					VALUES
					  (19771, '+20', 'مصر', 'Egypt', 7, '["099"]', 1, 1),
					  (19772, '+218', 'ليبيا', 'Libya', 10,
					   '["091","092","093","094","095","096"]', 1, 2),
					  (19774, '+964', 'العراق', 'Iraq', 10, '["077"]', 0, 4)
					""");
			// The delimiter-separated prefix form is a read-compatibility path,
			// not something the current schema will accept: phone_countries
			// carries CHECK (json_valid(phone_prefixes)), so only a row written
			// before that constraint existed can look like this. Seeding it
			// needs the check suspended, which is exactly the point -- PHP's
			// decoder still has to read such a row, and so does Java.
			st.execute("SET SESSION check_constraint_checks = 0");
			st.execute("""
					INSERT INTO phone_countries
					  (id, country_code, name_ar, name_en, phone_length, phone_prefixes, is_active, sort_order)
					VALUES (19773, '+973', 'البحرين', 'Bahrain', 8, '033 034;035', 1, 3)
					""");
			st.execute("SET SESSION check_constraint_checks = 1");
			st.execute("""
					INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
					  (19701, 'Phone Co', '+201000019701', 'active', '2025-01-15 09:00:00'),
					  (19702, 'Phone Co Two', '+201000019702', 'active', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (19711, 19701, 'Phone Branch', 1, '2025-03-01 10:00:00'),
					  (19712, 19702, 'Phone Branch Two', 1, '2025-03-01 10:00:00')
					""");
			insertEmployee(st, ACCEPTED, COMPANY, BRANCH, "'01012345678'", "'accepted'");
			insertEmployee(st, FORMATTED, COMPANY, BRANCH, "'+20 (10) 9999-9999'", "'accepted'");
			insertEmployee(st, WITHOUT_TRUNK_ZERO, COMPANY, BRANCH, "'1066666666'", "'accepted'");
			insertEmployee(st, SHARED_DIGITS, COMPANY, BRANCH, "'0502345678'", "'accepted'");
			// join_request_status is NOT NULL DEFAULT 'accepted' in the vendored
			// schema (line 448), so PHP's COALESCE(...,'accepted') can only ever
			// be defensive here -- a NULL is not reachable through this schema,
			// and the test says so rather than faking one. What is reachable is
			// the default itself, written by an insert that omits the column.
			insertEmployeeWithDefaultJoinStatus(st, LEGACY_DEFAULT_STATUS, COMPANY, BRANCH, "'01044444444'");
			insertEmployee(st, REJECTED, COMPANY, BRANCH, "'01033333333'", "'rejected'");
			insertEmployee(st, REJECTED_INTERNATIONAL, COMPANY, BRANCH, "'201022222222'", "'rejected'");
			// Another company's employee: uniqueness is global, not tenant-scoped.
			insertEmployee(st, 197021L, 19702L, 19712L, "'01055555555'", "'accepted'");
		}
	}

	private static void insertEmployeeWithDefaultJoinStatus(
			Statement st, long id, long companyId, long branchId, String phone) throws Exception {
		st.execute("""
				INSERT INTO employees
				  (id, company_id, branch_id, employee_code, first_name, last_name, phone, country_code,
				   password_hash, token_version, role, is_active, created_at)
				VALUES (%d, %d, %d, '%d', 'Phone', 'Subject', %s, '+20',
				   '$2y$10$abcdefghijklmnopqrstuv', 1, 'employee', 1, '2025-05-01 09:00:00')
				""".formatted(id, companyId, branchId, id, phone));
	}

	private static void insertEmployee(
			Statement st, long id, long companyId, long branchId, String phone, String joinStatus) throws Exception {
		st.execute("""
				INSERT INTO employees
				  (id, company_id, branch_id, employee_code, first_name, last_name, phone, country_code,
				   password_hash, token_version, role, is_active, join_request_status, created_at)
				VALUES (%d, %d, %d, '%d', 'Phone', 'Subject', %s, '+20',
				   '$2y$10$abcdefghijklmnopqrstuv', 1, 'employee', 1, %s, '2025-05-01 09:00:00')
				""".formatted(id, companyId, branchId, id, phone, joinStatus));
	}
}
