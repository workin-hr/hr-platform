package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * The legacy MySQL contract's value semantics, as pure functions.
 *
 * <p>These live apart from JPA on purpose. The awkward one --
 * {@code '0000-00-00'} -- cannot be handled by an
 * {@code AttributeConverter} at all: the JDBC driver raises on reading a
 * zero date, so the failure happens before any converter runs. The
 * legacy entity therefore carries the raw text and conversion happens
 * here, which is the same guard-on-the-raw-value rule the ETL already
 * proved (D-035/A2: test the staged text, never cast first and repair
 * after).
 *
 * <p>It also means the part of Phase 1 most likely to be subtly wrong is
 * testable without a database, on a machine with no Docker.
 */
class LegacyValuesTest {

	// ---------- tinyint(1) ----------

	/**
	 * Legacy's booleans are {@code tinyint(1)}, and PHP reads them as
	 * {@code (int) ($row[...] ?? 0) === 1} -- strict equality with 1, at
	 * every one of the five call sites that test {@code is_active}
	 * (hr-legacy/apis @ d113204). So 2 is <em>false</em>, not true.
	 *
	 * <p>Worth a test rather than an assumption: the obvious
	 * implementation, {@code value != 0}, agrees with legacy on every
	 * value the column normally holds and disagrees on every other one.
	 * A parity bug of exactly that shape would be invisible until the
	 * one row that has it.
	 */
	@Test
	void onlyExactlyOneIsTrue() {
		assertThat(LegacyValues.toBoolean(1)).isTrue();
		assertThat(LegacyValues.toBoolean(0)).isFalse();
		assertThat(LegacyValues.toBoolean(2)).isFalse();
		assertThat(LegacyValues.toBoolean(-1)).isFalse();
		assertThat(LegacyValues.toBoolean(null)).isFalse();
	}

	/** Writes stay inside the two values legacy actually uses. */
	@Test
	void writingABooleanProducesOneOrZero() {
		assertThat(LegacyValues.fromBoolean(true)).isEqualTo(1);
		assertThat(LegacyValues.fromBoolean(false)).isEqualTo(0);
	}

	// ---------- PHP scalar numeric casts ----------

	@Test
	void phpIntegerCastConsumesLeadingNumericTextAndTruncatesTowardZero() {
		assertThat(LegacyValues.toPhpLong("18811branch")).isEqualTo(18811L);
		assertThat(LegacyValues.toPhpLong("  -7.9 hours")).isEqualTo(-7L);
		assertThat(LegacyValues.toPhpLong("1e3suffix")).isEqualTo(1000L);
		assertThat(LegacyValues.toPhpLong(7.9)).isEqualTo(7L);
	}

	@Test
	void phpFloatCastConsumesLeadingNumericTextAndWhitespace() {
		assertThat(LegacyValues.toPhpDecimal("7.5hours")).isEqualByComparingTo("7.5");
		assertThat(LegacyValues.toPhpDecimal("  .75  ")).isEqualByComparingTo("0.75");
		assertThat(LegacyValues.toPhpDecimal("1e2days")).isEqualByComparingTo("100");
		assertThat(LegacyValues.toPhpDecimal(new BigDecimal("6.555")))
				.isEqualByComparingTo("6.555");
	}

	@Test
	void phpNumericCastsMapBooleansMalformedEmptyZeroAndNull() {
		assertThat(LegacyValues.toPhpLong(true)).isEqualTo(1L);
		assertThat(LegacyValues.toPhpLong(false)).isZero();
		assertThat(LegacyValues.toPhpDecimal(true)).isEqualByComparingTo(BigDecimal.ONE);
		assertThat(LegacyValues.toPhpDecimal(false)).isEqualByComparingTo(BigDecimal.ZERO);

		for (Object value : new Object[] {"abc", "", "   ", "0", 0, null}) {
			assertThat(LegacyValues.toPhpLong(value)).isZero();
			assertThat(LegacyValues.toPhpDecimal(value)).isEqualByComparingTo(BigDecimal.ZERO);
		}
	}

	// ---------- zero dates ----------

	/**
	 * MySQL permits {@code '0000-00-00'}; PostgreSQL and
	 * {@code java.time} do not. 22 {@code hire_date} and 2
	 * {@code birth_date} rows carry it
	 * (docs/migration/invalid-date-analysis.md), and Phase 1 must read
	 * them -- so it maps to null, which is what the column means: no
	 * date recorded.
	 */
	@Test
	void legacyZeroDatesReadAsNoDate() {
		assertThat(LegacyValues.toDate("0000-00-00")).isNull();
		assertThat(LegacyValues.toDate("0000-00-00 00:00:00")).isNull();
		assertThat(LegacyValues.toDate(null)).isNull();
		assertThat(LegacyValues.toDate("")).isNull();
		assertThat(LegacyValues.toDate("   ")).isNull();
	}

	@Test
	void realDatesReadAsThemselves() {
		assertThat(LegacyValues.toDate("1990-01-01")).isEqualTo(LocalDate.of(1990, 1, 1));
		assertThat(LegacyValues.toDate("2020-06-15 00:00:00")).isEqualTo(LocalDate.of(2020, 6, 15));
	}

	/**
	 * A partial zero date ({@code '2020-00-00'}) is deliberately NOT
	 * swallowed. Read-tolerance covers the malformed values that are
	 * known to exist, and the live probe found only the all-zero form on
	 * these columns. Treating every unparseable value as "no date" would
	 * turn an unknown data defect into a silently wrong answer on a
	 * hire date -- so anything else fails loudly and we find out.
	 */
	@Test
	void anUnknownMalformedDateFailsLoudlyRatherThanBecomingNull() {
		assertThatThrownBy(() -> LegacyValues.toDate("2020-00-00"))
				.isInstanceOf(LegacyValueException.class)
				.hasMessageContaining("2020-00-00");
	}

	@Test
	void writingADateNeverProducesAZeroDate() {
		assertThat(LegacyValues.fromDate(LocalDate.of(1990, 1, 1))).isEqualTo("1990-01-01");
		assertThat(LegacyValues.fromDate(null)).isNull();
	}

	// ---------- enums ----------

	enum Role { COMPANY_ADMIN, HR, MANAGER, EMPLOYEE }

	/**
	 * Legacy enums are lower snake case
	 * ({@code enum('company_admin','hr','manager','employee')}); the Java
	 * domain uses the Java convention. The mapping is 1:1 by name, and
	 * deliberately not a collapse -- the same rule D-036 set for
	 * {@code gender}.
	 */
	@Test
	void legacyEnumValuesMapOneToOneByName() {
		assertThat(LegacyValues.toEnum(Role.class, "company_admin")).isEqualTo(Role.COMPANY_ADMIN);
		assertThat(LegacyValues.toEnum(Role.class, "hr")).isEqualTo(Role.HR);
		assertThat(LegacyValues.toEnum(Role.class, "employee")).isEqualTo(Role.EMPLOYEE);
	}

	/**
	 * {@code ''} is what MySQL stores for an invalid enum value in
	 * non-strict mode -- the same placeholder D-036 had to handle for
	 * {@code gender}. It means "no value", not a value.
	 */
	@Test
	void theEmptyEnumPlaceholderAndNullBothMeanNoValue() {
		assertThat(LegacyValues.toEnum(Role.class, "")).isNull();
		assertThat(LegacyValues.toEnum(Role.class, null)).isNull();
	}

	/**
	 * An enum value legacy has and Java does not is a schema drift the
	 * build should surface, not a silent null that reads as "unset".
	 */
	@Test
	void anUnknownEnumValueFailsLoudly() {
		assertThatThrownBy(() -> LegacyValues.toEnum(Role.class, "auditor"))
				.isInstanceOf(LegacyValueException.class)
				.hasMessageContaining("auditor");
	}

	@Test
	void writingAnEnumProducesTheLegacySpelling() {
		assertThat(LegacyValues.fromEnum(Role.COMPANY_ADMIN)).isEqualTo("company_admin");
		assertThat(LegacyValues.fromEnum(null)).isNull();
	}

}
