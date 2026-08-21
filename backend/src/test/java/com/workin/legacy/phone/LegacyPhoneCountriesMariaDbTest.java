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
	void aConfiguredCountryValidatesOnItsOwnLengthAndPrefixes() {
		// +218 is seeded with length 10 and JSON prefixes 091..096.
		assertThat(numbers.isValidLocal("+218", "0912345678")).isTrue();
		assertThat(numbers.isValidLocal("+218", "0962345678")).isTrue();
		// Wrong length, right prefix.
		assertThat(numbers.isValidLocal("+218", "091234567")).isFalse();
		assertThat(numbers.isValidLocal("+218", "09123456789")).isFalse();
		// Right length, wrong prefix.
		assertThat(numbers.isValidLocal("+218", "0812345678")).isFalse();
	}

	@Test
	void aMissingLeadingZeroIsRestoredFromTheConfiguredPrefix() {
		// length - 1 digits that match a configured prefix without its zero.
		assertThat(numbers.normalizeLocal("+218", "912345678")).isEqualTo("0912345678");
		assertThat(numbers.isValidLocal("+218", "912345678")).isTrue();
		// The same shape with a prefix that is not configured stays as typed
		// and therefore fails the length check.
		assertThat(numbers.normalizeLocal("+218", "812345678")).isEqualTo("812345678");
		assertThat(numbers.isValidLocal("+218", "812345678")).isFalse();
	}

	@Test
	void aPrefixListStoredAsADelimitedStringWorksToo() {
		// +973 is seeded with "033 034;035" rather than JSON, which json_decode
		// rejects and PHP splits on [\s,;]+.
		assertThat(LegacyPhoneNumbers.decodePrefixes(countries.find("+973").orElseThrow().phonePrefixes()))
				.containsExactly("033", "034", "035");
		assertThat(numbers.isValidLocal("+973", "03312345")).isTrue();
		assertThat(numbers.isValidLocal("+973", "03612345")).isFalse();
	}

	@Test
	void egyptIgnoresTheTableAndKeepsItsOwnRule() {
		// phone_country_is_valid_local() decides +20 by regex "regardless of DB
		// prefix quirks" -- the seeded +20 row carries a deliberately wrong
		// length and prefix set, and Egyptian numbers still behave.
		assertThat(numbers.isValidLocal("+20", "01012345678")).isTrue();
		assertThat(numbers.isValidLocal("+20", "01512345678")).isTrue();
		assertThat(numbers.isValidLocal("+20", "01312345678")).isFalse();
		assertThat(numbers.isValidLocal("+20", "0101234567")).isFalse();

		// Normalization restores the leading zero and strips a pasted dial code.
		assertThat(numbers.normalizeLocal("+20", "1012345678")).isEqualTo("01012345678");
		assertThat(numbers.normalizeLocal("+20", "201012345678")).isEqualTo("01012345678");
		assertThat(numbers.normalizeLocal("20", "201012345678")).isEqualTo("01012345678");
		// An unrecognised Egyptian number is stored as typed, and then rejected.
		assertThat(numbers.normalizeLocal("+20", "01312345678")).isEqualTo("01312345678");
	}

	@Test
	void anUnknownDialCodeFallsBackToTheHardCodedRules() {
		// +966 has no row here, so phone_is_valid_local_legacy() decides.
		assertThat(countries.find("+966")).isEmpty();
		assertThat(numbers.isValidLocal("+966", "0512345678")).isTrue();
		assertThat(numbers.isValidLocal("+966", "0412345678")).isFalse();
		// A country the fallback rules do not know is rejected outright.
		assertThat(numbers.isValidLocal("+999", "12345678")).isFalse();
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

		// The fallback definitions are the ones that then validate.
		assertThat(fallbackNumbers.isValidLocal("+966", "0512345678")).isTrue();
		assertThat(fallbackNumbers.isValidLocal("+966", "051234567")).isFalse();
		assertThat(fallbackNumbers.isValidLocal("+971", "0501234567")).isTrue();
		assertThat(fallbackNumbers.isValidLocal("+971", "0511234567")).isFalse();
		assertThat(fallbackNumbers.isValidLocal("+218", "0912345678")).isTrue();
	}

	@Test
	void globalPhoneUniquenessMatchesEveryStoredSpelling() {
		// The stored number itself.
		assertThat(employees.phoneExistsGlobally("01012345678", null)).isTrue();
		// Every lookup variant of it.
		assertThat(employees.phoneExistsGlobally("1012345678", null)).isTrue();
		assertThat(employees.phoneExistsGlobally("201012345678", null)).isTrue();
		// Formatting in the request is stripped before matching.
		assertThat(employees.phoneExistsGlobally("+20 (10) 1234-5678", null)).isTrue();
		// Formatting already in the column is stripped by the SQL expression.
		assertThat(employees.phoneExistsGlobally("01099999999", null)).isTrue();
		// An unrelated number is free.
		assertThat(employees.phoneExistsGlobally("01111111111", null)).isFalse();
		// Blank input is not a match, it is "no phone".
		assertThat(employees.phoneExistsGlobally("", null)).isFalse();
		assertThat(employees.phoneExistsGlobally("   ", null)).isFalse();
	}

	@Test
	void globalPhoneUniquenessIsGlobalButSkipsRejectedJoinRequests() {
		// The row is in another company entirely -- there is no company_id
		// predicate, because employees.phone is globally unique.
		assertThat(employees.phoneExistsGlobally("01055555555", null)).isTrue();
		// The schema default ('accepted', written by omitting the column) counts.
		assertThat(employees.phoneExistsGlobally("01044444444", null)).isTrue();
		// A rejected join request does not reserve the number.
		assertThat(employees.phoneExistsGlobally("01033333333", null)).isFalse();
		// The exclusion is only applied for a positive id.
		assertThat(employees.phoneExistsGlobally("01012345678", ACCEPTED)).isFalse();
		assertThat(employees.phoneExistsGlobally("01012345678", 0L)).isTrue();
		assertThat(employees.phoneExistsGlobally("01012345678", -5L)).isTrue();
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
			// join_request_status is NOT NULL DEFAULT 'accepted' in the vendored
			// schema (line 448), so PHP's COALESCE(...,'accepted') can only ever
			// be defensive here -- a NULL is not reachable through this schema,
			// and the test says so rather than faking one. What is reachable is
			// the default itself, written by an insert that omits the column.
			insertEmployeeWithDefaultJoinStatus(st, LEGACY_DEFAULT_STATUS, COMPANY, BRANCH, "'01044444444'");
			insertEmployee(st, REJECTED, COMPANY, BRANCH, "'01033333333'", "'rejected'");
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
