package com.workin.legacy.employees;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.workin.legacy.AbstractLegacyMySqlTest;
import com.workin.legacy.LegacyValues;

/**
 * Proves the legacy persistence pattern against a real MariaDB running
 * the real legacy schema -- the Phase 1 substrate, end to end.
 *
 * <p>MariaDB and not MySQL because production is MariaDB 11.8.8,
 * verified read-only against the live host (D-037). The schema applied
 * here is the vendored copy of {@code hr-legacy}'s, byte-identical to it
 * (scripts/check_legacy_schema_drift.py), so what this exercises is the
 * production storage contract rather than an approximation of it.
 *
 * <p><b>Deliberately raw JDBC, not JPA.</b> The thing under test is
 * whether the legacy representations survive a real driver at all:
 * whether {@code '0000-00-00'} can be read as text without the driver
 * raising, whether {@code tinyint(1)} arrives as something
 * {@link com.workin.legacy.LegacyValues#toBoolean} can read,
 * whether the enums arrive as their lower-case spellings. Those are
 * driver-level facts, and asserting them here means that when the Spring
 * context is later pointed at MySQL, a failure is unambiguously in the
 * wiring rather than in the contract.
 *
 * <p>The Spring/JPA half of the adapter is not exercised yet on purpose:
 * the application still boots against PostgreSQL until auth/authz is
 * reworked, so there is no MySQL context to load it into. That step
 * comes next, and this test is what it will build on.
 */
class LegacyEmployeeAdapterTest extends AbstractLegacyMySqlTest {

	@BeforeAll
	static void seed() throws Exception {
		seedAsLegacyWould("""
				INSERT INTO companies (id, company_name, phone, status, created_at)
				VALUES (1, 'Legacy Co', '+201000000001', 'active', '2025-01-15 09:00:00')
				""", """
				INSERT INTO branches (id, company_id, name, is_active, created_at)
				VALUES (7, 1, 'HQ', 1, '2025-03-01 10:00:00')
				""", """
				INSERT INTO employees
				  (id, company_id, branch_id, employee_code, first_name, last_name, phone,
				   role, birth_date, gender, hire_date, is_active, is_mobile_attendance_enabled,
				   can_check_in_any_branch, join_request_status, token_version, created_at)
				VALUES
				  (11, 1, 7, 'EMP-001', 'Sara', 'Ali', '+201100000011',
				   'hr', '1990-01-01', 'female', '2020-06-15', 1, 1, 0, 'accepted', 1,
				   '2025-04-01 08:00:00'),
				  (12, 1, 7, 'EMP-002', 'Omar', 'Nabil', '+201200000012',
				   'employee', '0000-00-00', '', '0000-00-00', 2, 0, 1, 'pending', 1,
				   '2025-04-02 08:00:00')
				""");
	}

	/**
	 * The whole reason the two date columns are mapped as text. If this
	 * fails, the driver is refusing the value before any application
	 * code sees it, and the adapter's design premise is wrong.
	 */
	@Test
	void aLegacyZeroDateCanBeReadAsTextWithoutTheDriverRaising() throws Exception {
		assertThat(employeeColumn(12, "birth_date")).isEqualTo("0000-00-00");
		assertThat(employeeColumn(12, "hire_date")).isEqualTo("0000-00-00");
		assertThat(LegacyValues.toDate(employeeColumn(12, "birth_date")))
				.isNull();
	}

	/** ...and a real date still round-trips to a real date. */
	@Test
	void realDatesSurviveTheSameTextPath() throws Exception {
		assertThat(LegacyValues.toDate(employeeColumn(11, "birth_date")))
				.isEqualTo(LocalDate.of(1990, 1, 1));
		assertThat(LegacyValues.toDate(employeeColumn(11, "hire_date")))
				.isEqualTo(LocalDate.of(2020, 6, 15));
	}

	/**
	 * Employee 12 holds {@code is_active = 2}. Legacy reads that as
	 * false ({@code (int) (...) === 1}), and so must this -- the case
	 * that separates the correct implementation from {@code != 0}.
	 */
	@Test
	void tinyintBooleansFollowLegacysStrictEqualityWithOne() throws Exception {
		assertThat(com.workin.legacy.LegacyValues
				.toBoolean(Integer.valueOf(employeeColumn(11, "is_active")))).isTrue();
		assertThat(com.workin.legacy.LegacyValues
				.toBoolean(Integer.valueOf(employeeColumn(12, "is_active")))).isFalse();
	}

	/**
	 * Enums arrive as their legacy lower-case spellings, and the
	 * {@code ''} placeholder MySQL stores for an invalid enum survives
	 * as {@code ''} rather than becoming null in the driver -- which is
	 * what makes it the application's decision to read it as "no value".
	 */
	@Test
	void enumsArriveInTheirLegacySpellingIncludingTheEmptyPlaceholder() throws Exception {
		assertThat(employeeColumn(11, "role")).isEqualTo("hr");
		assertThat(employeeColumn(11, "gender")).isEqualTo("female");
		assertThat(employeeColumn(12, "gender")).isEqualTo("");

		assertThat(com.workin.legacy.LegacyValues
				.toEnum(LegacyEmployee.Role.class, employeeColumn(11, "role")))
				.isEqualTo(LegacyEmployee.Role.HR);
		assertThat(com.workin.legacy.LegacyValues
				.toEnum(LegacyEmployee.Gender.class, employeeColumn(12, "gender")))
				.isNull();
	}

	/**
	 * The legacy schema applies unmodified. This is the assertion that
	 * would catch the contract being quietly adjusted to suit Java --
	 * the thing the Database Rule exists to prevent.
	 */
	@Test
	void theVendoredLegacySchemaAppliesToARealMariaDbUnmodified() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(
						"SELECT count(*) FROM information_schema.tables "
								+ "WHERE table_schema = DATABASE()")) {
			rs.next();
			assertThat(rs.getInt(1)).isEqualTo(42);
		}
	}

}
