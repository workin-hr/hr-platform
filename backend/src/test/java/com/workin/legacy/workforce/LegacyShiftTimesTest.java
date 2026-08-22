package com.workin.legacy.workforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.workin.legacy.wire.LegacyApiException;

/**
 * {@link LegacyShiftTimes} against {@code shift_times.php}'s own arithmetic.
 *
 * <p>The cases that matter are the ones where a reasonable implementation would
 * differ from PHP: the captured-but-unused seconds group, the range checks that
 * live in the body rather than the pattern, the equal-times branch, and the
 * boundary at exactly sixteen hours.
 */
class LegacyShiftTimesTest {

	@Nested
	@DisplayName("parsing")
	class Parsing {

		@ParameterizedTest(name = "{0} -> {1} minutes")
		@CsvSource({
			"'00:00', 0",
			"'09:00', 540",
			"'9:00', 540",
			"'23:59', 1439",
			"'  09:00  ', 540",
		})
		void parsesTheAcceptedShapes(String raw, int expected) {
			assertThat(LegacyShiftTimes.toMinutes(raw)).isEqualTo(expected);
		}

		/**
		 * The whole point of the third capture group: it matches so an
		 * {@code H:i:s} value from a {@code TIME} column parses, and it is then
		 * discarded, so the seconds never reach the arithmetic.
		 */
		@ParameterizedTest(name = "{0} parses to the same minute as its H:i prefix")
		@CsvSource({
			"'09:00:00', 540",
			"'09:00:59', 540",
			"'23:59:59', 1439",
		})
		void capturesSecondsAndThenIgnoresThem(String raw, int expected) {
			assertThat(LegacyShiftTimes.toMinutes(raw)).isEqualTo(expected);
		}

		@Test
		void secondsCannotLengthenAWindow() {
			// 09:00:00 -> 17:00:59 is 8h00m00s to this validator, not 8h00m59s.
			assertThat(LegacyShiftTimes.durationMinutes("09:00:00", "17:00:59"))
					.isEqualTo(LegacyShiftTimes.durationMinutes("09:00", "17:00"))
					.isEqualTo(480);
		}

		/**
		 * {@code \d{1,2}} matches these; the explicit {@code $h > 23} and
		 * {@code $i > 59} comparisons are what reject them. A pattern-only port
		 * would accept every one of these.
		 */
		@ParameterizedTest(name = "{0} is out of range")
		@ValueSource(strings = { "24:00", "99:00", "23:60", "00:99" })
		void rejectsOutOfRangeComponentsThePatternWouldAccept(String raw) {
			assertThat(LegacyShiftTimes.toMinutes(raw)).isNull();
		}

		@ParameterizedTest(name = "{0} is unparseable")
		@ValueSource(strings = { "", "   ", "9", "09:0", "09:000", "0900", "09-00", "nine", "09:00:", "09:00:0" })
		void rejectsUnparseableShapes(String raw) {
			assertThat(LegacyShiftTimes.toMinutes(raw)).isNull();
		}

		@Test
		void nullIsNotAnException() {
			assertThat(LegacyShiftTimes.toMinutes(null)).isNull();
		}

	}

	@Nested
	@DisplayName("duration")
	class Duration {

		@ParameterizedTest(name = "{0} -> {1} is {2} minutes")
		@CsvSource({
			"'09:00', '17:00', 480",
			"'00:00', '23:59', 1439",
			"'09:00', '09:01', 1",
		})
		void sameDayIsASubtraction(String start, String end, int expected) {
			assertThat(LegacyShiftTimes.durationMinutes(start, end)).isEqualTo(expected);
		}

		@ParameterizedTest(name = "{0} -> {1} wraps to {2} minutes")
		@CsvSource({
			"'22:00', '06:00', 480",
			"'23:59', '00:00', 1",
			"'00:01', '00:00', 1439",
		})
		void anEarlierEndWrapsOvernight(String start, String end, int expected) {
			assertThat(LegacyShiftTimes.durationMinutes(start, end)).isEqualTo(expected);
		}

		@Test
		void equalTimesAreZeroRatherThanAFullDay() {
			assertThat(LegacyShiftTimes.durationMinutes("09:00", "09:00")).isZero();
			// and the seconds still do not separate them
			assertThat(LegacyShiftTimes.durationMinutes("09:00:00", "09:00:59")).isZero();
		}

		@Test
		void nullPropagatesFromEitherOperand() {
			assertThat(LegacyShiftTimes.durationMinutes("bad", "17:00")).isNull();
			assertThat(LegacyShiftTimes.durationMinutes("09:00", "bad")).isNull();
		}

	}

	@Nested
	@DisplayName("assertDailyWindowValid")
	class Assertion {

		@ParameterizedTest(name = "{0} -> {1} is accepted")
		@CsvSource({
			"'09:00', '17:00'",
			"'22:00', '06:00'",
			"'09:00', '09:01'",
			"'09:00:00', '17:00:59'",
		})
		void acceptsAValidWindow(String start, String end) {
			assertThatCode(() -> LegacyShiftTimes.assertDailyWindowValid(start, end))
					.doesNotThrowAnyException();
		}

		/** {@code $duration > 16 * 60}, so the boundary itself passes. */
		@Test
		void sixteenHoursExactlyIsAcceptedAndOneMinuteMoreIsNot() {
			assertThatCode(() -> LegacyShiftTimes.assertDailyWindowValid("08:00", "00:00"))
					.doesNotThrowAnyException();

			assertThatThrownBy(() -> LegacyShiftTimes.assertDailyWindowValid("08:00", "00:01"))
					.isInstanceOfSatisfying(LegacyApiException.class, ex -> {
						assertThat(ex.getStatus()).isEqualTo(400);
						assertThat(ex.getMessageKey()).isEqualTo("shift_exceeds_max_hours");
					});
		}

		@Test
		void equalTimesFailAsAnEmptyWindowNotAsBadInput() {
			assertThatThrownBy(() -> LegacyShiftTimes.assertDailyWindowValid("09:00", "09:00"))
					.isInstanceOfSatisfying(LegacyApiException.class, ex -> {
						assertThat(ex.getStatus()).isEqualTo(400);
						assertThat(ex.getMessageKey()).isEqualTo("shift_end_must_be_after_start");
					});
		}

		@ParameterizedTest(name = "{0} -> {1} is invalid_input")
		@CsvSource({
			"'24:00', '17:00'",
			"'09:00', '23:60'",
			"'nine', '17:00'",
			"'', '17:00'",
		})
		void unparseableInputIsInvalidInputRatherThanAWindowError(String start, String end) {
			assertThatThrownBy(() -> LegacyShiftTimes.assertDailyWindowValid(start, end))
					.isInstanceOfSatisfying(LegacyApiException.class, ex -> {
						assertThat(ex.getStatus()).isEqualTo(400);
						assertThat(ex.getMessageKey()).isEqualTo("invalid_input");
					});
		}

	}

}
