package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

	// ---------- PHP numeric casts and array semantics ----------

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

	@Test
	void phpNumericCastsUsePhpArrayTruthinessForArraysCollectionsAndMaps() {
		for (Object empty : new Object[] {new Object[0], new int[0], List.of(), Map.of()}) {
			assertThat(LegacyValues.toPhpLong(empty)).isZero();
			assertThat(LegacyValues.toPhpDecimal(empty)).isEqualByComparingTo(BigDecimal.ZERO);
		}

		for (Object nonEmpty : new Object[] {
				new Object[] {null}, new int[] {0}, List.of(0), Map.of("key", 0)}) {
			assertThat(LegacyValues.toPhpLong(nonEmpty)).isEqualTo(1L);
			assertThat(LegacyValues.toPhpDecimal(nonEmpty)).isEqualByComparingTo(BigDecimal.ONE);
		}
	}

	@Test
	void phpIntegerCastSaturatesAtThe64BitPlatformBoundsInsteadOfWrapping() {
		assertThat(LegacyValues.toPhpLong("9223372036854775807")).isEqualTo(Long.MAX_VALUE);
		assertThat(LegacyValues.toPhpLong("9223372036854775808")).isEqualTo(Long.MAX_VALUE);
		assertThat(LegacyValues.toPhpLong("42.42e42hours")).isEqualTo(Long.MAX_VALUE);

		assertThat(LegacyValues.toPhpLong("-9223372036854775808")).isEqualTo(Long.MIN_VALUE);
		assertThat(LegacyValues.toPhpLong("-9223372036854775809")).isEqualTo(Long.MIN_VALUE);
		assertThat(LegacyValues.toPhpLong("-42.42e42hours")).isEqualTo(Long.MIN_VALUE);
	}

	@Test
	void phpEmptyDistinguishesEmptyAndNonEmptyJsonDecodedValues() {
		for (Object empty : new Object[] {
				null, false, 0, 0L, -0.0d, BigDecimal.ZERO, "", "0",
				new Object[0], new int[0], List.of(), Map.of()}) {
			assertThat(LegacyValues.isPhpEmpty(empty)).isTrue();
		}

		for (Object nonEmpty : new Object[] {
				true, 1, -1, new BigDecimal("0.01"), "00", " ",
				new Object[] {null}, new int[] {0}, List.of(0), Map.of("key", 0)}) {
			assertThat(LegacyValues.isPhpEmpty(nonEmpty)).isFalse();
		}
	}

	@Test
	void phpArrayIterationUsesElementsAndAssociativeMapValues() {
		Map<String, Object> associative = new LinkedHashMap<>();
		associative.put("first", "18811branch");
		associative.put("second", 18812);

		assertThat(LegacyValues.phpArrayValues(new int[] {1, 2}).toArray()).containsExactly(1, 2);
		assertThat(LegacyValues.phpArrayValues(List.of("a", "b")).toArray()).containsExactly("a", "b");
		assertThat(LegacyValues.phpArrayValues(associative).toArray()).containsExactly("18811branch", 18812);
		assertThat(LegacyValues.phpArrayValues("not-an-array")).isEmpty();
	}

	@Test
	void phpStringCastPreservesJsonScalarSemantics() {
		assertThat(LegacyValues.toPhpString(null)).isEmpty();
		assertThat(LegacyValues.toPhpString(false)).isEmpty();
		assertThat(LegacyValues.toPhpString(true)).isEqualTo("1");
		assertThat(LegacyValues.toPhpString(42)).isEqualTo("42");
		assertThat(LegacyValues.toPhpString(7.0)).isEqualTo("7");
		assertThat(LegacyValues.toPhpString(new BigDecimal("7.25"))).isEqualTo("7.25");
		assertThat(LegacyValues.toPhpString(0.0000001d)).isEqualTo("1.0E-7");
		assertThat(LegacyValues.toPhpString(" Normal ")).isEqualTo(" Normal ");
		assertThat(LegacyValues.toPhpString("")).isEmpty();
	}

	@Test
	void phpStringCastUsesArrayRatherThanJavaCollectionOrMapRendering() {
		for (Object value : new Object[] {
				new Object[0], new int[] {1}, List.of(), List.of("value"), Map.of("key", "value")}) {
			assertThat(LegacyValues.toPhpString(value)).isEqualTo("Array");
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


	/**
	 * {@code filter_var(..., FILTER_VALIDATE_BOOLEAN)} strips a <b>third</b>
	 * whitespace set: not {@link String#trim}'s and not {@code phpTrim}'s.
	 *
	 * <p>{@code php_filter_boolean()} skips space, tab, newline, carriage
	 * return, vertical tab and form feed. It <b>does</b> take form feed, which
	 * PHP's own {@code trim()} does not -- and it does <b>not</b> take NUL,
	 * which both {@code trim()} and Java's {@code String.trim()} do.
	 *
	 * <p>NUL is therefore the observable divergence, and the reason this needed
	 * its own helper rather than either existing one.
	 */
	@Test
	void filterValidateBooleanUsesItsOwnWhitespaceSet() {
		assertThat(LegacyValues.toPhpFilterBoolean("\ftrue\f"))
				.as("form feed IS stripped by the filter, unlike PHP's trim()")
				.isTrue();
		assertThat(LegacyValues.toPhpFilterBoolean("\u000Btrue")).isTrue();
		assertThat(LegacyValues.toPhpFilterBoolean("  true\t")).isTrue();

		assertThat(LegacyValues.toPhpFilterBoolean("\0true"))
				.as("NUL is NOT stripped, so the value is unrecognised and false -- "
						+ "String.trim() would have stripped it and returned true")
				.isFalse();
		assertThat(LegacyValues.toPhpFilterBoolean("true\0")).isFalse();

		assertThat(LegacyValues.toPhpFilterBoolean("yes")).isTrue();
		assertThat(LegacyValues.toPhpFilterBoolean("on")).isTrue();
		assertThat(LegacyValues.toPhpFilterBoolean("1")).isTrue();
		assertThat(LegacyValues.toPhpFilterBoolean("no")).isFalse();
		assertThat(LegacyValues.toPhpFilterBoolean("")).isFalse();
	}
}
