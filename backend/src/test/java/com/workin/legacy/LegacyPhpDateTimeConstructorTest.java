package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code new DateTimeImmutable($raw)} against the bounded
 * {@link LegacyPhpStrtotime} grammar.
 *
 * <p>{@code schedule_generate_for_employee()} constructs its range with the
 * <b>constructor</b>, not with {@code strtotime()}, so before reusing the
 * existing grammar for it the two had to be shown to agree. They do: measured
 * on PHP 8.3 under {@code Etc/GMT-2} with the reference frozen at
 * {@code 2026-08-23 14:37:19}, the constructor succeeds exactly where
 * {@code strtotime()} does, with the same value -- <b>except for the empty
 * string</b>, which is the one case where they differ and the one case this
 * caller can actually reach.
 *
 * <table>
 * <caption>Measured: {@code new DateTimeImmutable($raw)} vs {@code strtotime($raw, $ref)}</caption>
 * <tr><th>input</th><th>constructor</th><th>strtotime</th></tr>
 * <tr><td>{@code 2026-04-26}</td><td>2026-04-26</td><td>same</td></tr>
 * <tr><td>{@code 2026/04/26}</td><td>2026-04-26</td><td>same</td></tr>
 * <tr><td>{@code 26-04-2026}</td><td>2026-04-26</td><td>same</td></tr>
 * <tr><td>{@code 20260426}</td><td>2026-04-26</td><td>same</td></tr>
 * <tr><td>{@code 2026-4-6}</td><td>2026-04-06</td><td>same</td></tr>
 * <tr><td>{@code 2026-02-30}</td><td>2026-03-02 (rolls)</td><td>same</td></tr>
 * <tr><td>{@code 2026-04-31}</td><td>2026-05-01 (rolls)</td><td>same</td></tr>
 * <tr><td>{@code 26 Apr 2026}, {@code Apr 26 2026}</td><td>2026-04-26</td><td>same</td></tr>
 * <tr><td>{@code today}, {@code tomorrow}, {@code yesterday}</td><td>relative</td><td>same</td></tr>
 * <tr><td>{@code +1 day} and its family</td><td>relative</td><td>same -- <b>not implemented</b>, D-094</td></tr>
 * <tr><td>{@code 1990}</td><td>1990-08-23 (year branch)</td><td>same</td></tr>
 * <tr><td>{@code 2026-13-01}, {@code 26/04/2026}, {@code oops}</td><td><b>throws</b></td><td>{@code false}</td></tr>
 * <tr><td>{@code ""}</td><td><b>now</b></td><td><b>{@code false}</b> -- the one divergence</td></tr>
 * </table>
 *
 * <p>So a caller that uses the constructor can reuse this grammar as long as it
 * handles the empty string itself. {@code 26/04/2026} throwing is worth
 * noticing: the day-first slash form the punch parser accepts is <b>not</b>
 * accepted here, and it is not accepted by the {@code DATE} column either --
 * three surfaces in one wave, and only the punch parser takes it.
 */
class LegacyPhpDateTimeConstructorTest {

	/** The reference the measurement was taken against. */
	private static final LocalDate TODAY = LocalDate.of(2026, 8, 23);

	@ParameterizedTest(name = "[{index}] {0} -> {1}")
	@CsvSource(delimiter = '|', value = {
		"2026-04-26          | 2026-04-26",
		"2026/04/26          | 2026-04-26",
		"26-04-2026          | 2026-04-26",
		"20260426            | 2026-04-26",
		"2026-4-6            | 2026-04-06",
		"2026-02-30          | 2026-03-02",
		"2026-04-31          | 2026-05-01",
		"26 Apr 2026         | 2026-04-26",
		"Apr 26 2026         | 2026-04-26",
		"2026-04-26 08:03:00 | 2026-04-26",
		"today               | 2026-08-23",
		"tomorrow            | 2026-08-24",
		"yesterday           | 2026-08-22",
		"1990                | 1990-08-23",
	})
	void theConstructorAndTheBoundedGrammarAgreeOnAcceptedValues(String raw, String expected) {
		assertThat(LegacyPhpStrtotime.dateOf(raw, TODAY)).isEqualTo(LocalDate.parse(expected));
	}

	/**
	 * Values the constructor <b>throws</b> on, and the grammar answers null for.
	 *
	 * <p>Null is how this grammar spells {@code strtotime() === false}, and the
	 * measurement shows the constructor throws for exactly those values. A
	 * caller that constructs is therefore correct to treat null as "PHP would
	 * have thrown".
	 */
	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = {"2026-13-01", "26/04/2026", "oops"})
	void theConstructorThrowsWhereTheGrammarAnswersNull(String raw) {
		assertThat(LegacyPhpStrtotime.dateOf(raw, TODAY)).isNull();
	}

	/**
	 * The eight-digit form's bounds, which are the ISO branch's.
	 *
	 * <p>Seven digits is not a short version of this -- {@code 2026042} is 11
	 * February 2026 -- and nine is rejected outright, so the branch is keyed on
	 * the exact width.
	 */
	@ParameterizedTest(name = "[{index}] {0} -> {1}")
	@CsvSource(delimiter = '|', value = {
		"20260426 | 2026-04-26",
		"20260431 | 2026-05-01",
		"20260229 | 2026-03-01",
		"20250229 | 2025-03-01",
		"20260000 | 2025-11-30",
	})
	void theEightDigitFormRollsLikeTheIsoBranch(String raw, String expected) {
		assertThat(LegacyPhpStrtotime.dateOf(raw, TODAY)).isEqualTo(LocalDate.parse(expected));
	}

	/** Month 13 is rejected there too, and nine digits is not the branch at all. */
	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = {"20261301", "202604261"})
	void theEightDigitFormRejectsWhatTheConstructorRejects(String raw) {
		assertThat(LegacyPhpStrtotime.dateOf(raw, TODAY)).isNull();
	}

	/**
	 * <b>Signed relative offsets are deliberately not implemented</b> (D-094).
	 *
	 * <p>PHP accepts {@code +1 day} here, so this is a real, measured
	 * divergence rather than an oversight -- but the family it belongs to is
	 * open-ended in exactly the way D-094 refuses to infer: {@code +1 fortnight},
	 * {@code +1 days ago}, {@code next day}, a bare {@code 1 day}, {@code ++1 day}
	 * and {@code + 1 day} all parse, and {@code +1 dayz} parses as {@code +1 day}
	 * plus the military timezone {@code z}, landing two hours later. That last
	 * one is the same timezone-token grammar D-094 already excludes.
	 *
	 * <p>The named keywords this grammar does implement -- {@code today},
	 * {@code tomorrow}, {@code yesterday}, {@code now} -- are closed and
	 * measured, and they are what the evidenced callers use. If a caller is ever
	 * evidenced sending an offset, that branch gets measured and added then.
	 */
	@ParameterizedTest(name = "[{index}] {0} is PHP-accepted and not implemented")
	@ValueSource(strings = {"+1 day", "-1 day", "+2 days", "1 day", "next day", "+1 fortnight"})
	void signedRelativeOffsetsStayOutsideTheGrammar(String raw) {
		assertThat(LegacyPhpStrtotime.dateOf(raw, TODAY))
				.describedAs("PHP parses %s; D-094 keeps the offset family out", raw)
				.isNull();
	}

	/**
	 * The one divergence, and the one a schedule range can reach.
	 *
	 * <p>{@code required()} rejects a literal empty string, but it accepts
	 * {@code "  "}, which {@code trim()} then makes empty. The constructor
	 * answers <b>now</b> for that; {@code strtotime('')} is {@code false}. So a
	 * caller using the constructor must special-case it rather than treating
	 * the grammar's null as a parse failure.
	 */
	@Test
	void theEmptyStringIsTheOneCaseWhereTheyDiffer() {
		assertThat(LegacyPhpStrtotime.dateOf("", TODAY))
				.describedAs("the grammar reports strtotime's false")
				.isNull();
		// A constructor caller must read that null as "today", not as a failure.
	}

}
