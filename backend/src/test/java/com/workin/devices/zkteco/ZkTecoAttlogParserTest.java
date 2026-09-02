package com.workin.devices.zkteco;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.workin.devices.DeviceAttendanceEvent;

class ZkTecoAttlogParserTest {

	private static final String SN = "BOCK200961014";

	/** The zone a claimed device is registered with; the epoch cases turn on it. */
	private static final ZoneId CAIRO = ZoneId.of("+02:00");

	/** The seven-field shape captured from a real X100-C (design section 5.2). */
	@Test
	void parsesTheCapturedSevenFieldLine() {
		ZkTecoAttlogParser.Result result = ZkTecoAttlogParser.parse(SN,
				"1\t2024-07-28 01:25:24\t0\t1\t\t0\t0\r\n1\t2024-07-28 10:41:21\t0\t1\t\t0\t0", CAIRO);

		assertThat(result.malformed()).isZero();
		assertThat(result.events()).hasSize(2);
		DeviceAttendanceEvent first = result.events().get(0);
		assertThat(first.pin()).isEqualTo("1");
		assertThat(first.punchedAtLocal()).isEqualTo(LocalDateTime.of(2024, 7, 28, 1, 25, 24));
		assertThat(first.statusCode()).isZero();
		assertThat(first.verifyCode()).isEqualTo(1);
		assertThat(first.workCode()).isNull();
		assertThat(first.rawLine()).isEqualTo("1\t2024-07-28 01:25:24\t0\t1\t\t0\t0");
	}

	@Test
	void parsesTheFiveFieldShapeAndKeepsAWorkCode() {
		DeviceAttendanceEvent event = ZkTecoAttlogParser.parse(SN, "123\t2024-01-01 08:00:00\t1\t15\tWC7\n", CAIRO).events().get(0);

		assertThat(event.statusCode()).isEqualTo(1);
		assertThat(event.verifyCode()).isEqualTo(15);
		assertThat(event.workCode()).isEqualTo("WC7");
	}

	@Test
	void onlyPinAndTimeAreRequired() {
		DeviceAttendanceEvent event = ZkTecoAttlogParser.parse(SN, "42\t2024-01-01 08:00:00", CAIRO).events().get(0);

		assertThat(event.pin()).isEqualTo("42");
		assertThat(event.statusCode()).isNull();
		assertThat(event.verifyCode()).isNull();
	}

	/**
	 * An epoch value is an instant, so it becomes a wall clock only through
	 * the device's own zone. Reading it as UTC and letting ingestion apply the
	 * zone afterwards would convert it twice: this device's clock read 10:00
	 * when the instant was 08:00Z, and the earlier version of this parser
	 * stored 08:00 as the local time and 06:00 as the instant.
	 */
	@Test
	void aUnixSecondsTimestampBecomesTheDevicesOwnWallClock() {
		DeviceAttendanceEvent event = ZkTecoAttlogParser.parse(SN, "7\t1704096000\t0\t1", CAIRO).events().get(0);

		assertThat(event.punchedAtLocal()).isEqualTo(LocalDateTime.of(2024, 1, 1, 10, 0, 0));
	}

	@Test
	void aStatusOrVerifyCodeOutsideSmallintIsDroppedRatherThanStored() {
		DeviceAttendanceEvent event = ZkTecoAttlogParser
				.parse(SN, "5\t2024-07-28 08:00:00\t40000\t-40000", CAIRO).events().get(0);

		assertThat(event.statusCode()).isNull();
		assertThat(event.verifyCode()).isNull();
	}

	/** Outside DATETIME's range the row could not be stored, so the line never becomes an event. */
	/** SMART resolution would quietly turn this into 29 February and store a real punch on the wrong day. */
	@Test
	void anImpossibleCalendarDateIsMalformedRatherThanRolledBack() {
		ZkTecoAttlogParser.Result result = ZkTecoAttlogParser.parse(SN, "5\t2024-02-30 08:00:00\t0\t1", CAIRO);

		assertThat(result.events()).isEmpty();
		assertThat(result.malformed()).isEqualTo(1);
	}

	/**
	 * Two distinct instants share one wall clock in an autumn overlap. Keying
	 * them by local time would collapse them onto one dedup key and silently
	 * drop a punch.
	 */
	@Test
	void twoEpochPunchesInADaylightSavingOverlapStayDistinct() {
		java.time.ZoneId cairo = java.time.ZoneId.of("Africa/Cairo");
		// Cairo's 2025 DST end: both instants are 23:59:59 local, an hour apart.
		DeviceAttendanceEvent first = ZkTecoAttlogParser.parse(SN, "9\t1761857999\t0\t1", cairo).events().get(0);
		DeviceAttendanceEvent second = ZkTecoAttlogParser.parse(SN, "9\t1761861599\t0\t1", cairo).events().get(0);

		assertThat(first.punchedAtLocal()).isEqualTo(second.punchedAtLocal());
		assertThat(first.punchedAtInstant()).isNotEqualTo(second.punchedAtInstant());
		assertThat(first.dedupKey()).isNotEqualTo(second.dedupKey());
	}

	@Test
	void aWallClockPunchCarriesNoInstantBecauseItHasNoOffset() {
		assertThat(ZkTecoAttlogParser.parse(SN, "5\t2024-07-28 08:00:00\t0\t1", CAIRO)
				.events().get(0).punchedAtInstant()).isNull();
	}

	@Test
	void aTimestampOutsideTheDatetimeYearRangeIsMalformed() {
		ZkTecoAttlogParser.Result result = ZkTecoAttlogParser.parse(SN, "5\t0999-07-28 08:00:00\t0\t1", CAIRO);

		assertThat(result.events()).isEmpty();
		assertThat(result.malformed()).isEqualTo(1);
	}

	@Test
	void malformedLinesAreQuarantinedWithoutLosingTheGoodOnes() {
		ZkTecoAttlogParser.Result result = ZkTecoAttlogParser.parse(SN, String.join("\n",
				"1001\t2024-07-28 08:00:00\t0\t1",
				"abc\t2024-07-28 08:00:00\t0\t1",          // PIN is not numeric
				"1002\tyesterday\t0\t1",                   // time is not a timestamp
				"just-one-field",                          // no tab at all
				"9".repeat(33) + "\t2024-07-28 08:00:00",   // PIN past the shared 32-digit limit
				"",
				"1003\t2024-07-28 09:00:00\t0\t1"), CAIRO);

		assertThat(result.malformed()).isEqualTo(4);
		assertThat(result.events()).extracting(DeviceAttendanceEvent::pin).containsExactly("1001", "1003");
	}

	@Test
	void statusAndVerifyOutsideASmallIntegerAreDroppedNotFatal() {
		DeviceAttendanceEvent event = ZkTecoAttlogParser.parse(SN, "5\t2024-07-28 08:00:00\tIN\t999999", CAIRO).events().get(0);

		assertThat(event.statusCode()).isNull();
		assertThat(event.verifyCode()).isNull();
	}

	@Test
	void anOverlongWorkCodeIsBounded() {
		String code = "W".repeat(80);
		DeviceAttendanceEvent event = ZkTecoAttlogParser.parse(SN, "5\t2024-07-28 08:00:00\t0\t1\t" + code, CAIRO).events().get(0);

		assertThat(event.workCode()).hasSize(32);
	}

	@Test
	void theDedupKeyIsStableAcrossRedeliveryAndDistinguishesStatus() {
		String line = "1\t2024-07-28 01:25:24\t0\t1\t\t0\t0";
		DeviceAttendanceEvent first = ZkTecoAttlogParser.parse(SN, line, CAIRO).events().get(0);
		DeviceAttendanceEvent again = ZkTecoAttlogParser.parse(SN, line, CAIRO).events().get(0);
		DeviceAttendanceEvent checkOut = ZkTecoAttlogParser.parse(SN, "1\t2024-07-28 01:25:24\t1\t1", CAIRO).events().get(0);
		DeviceAttendanceEvent otherDevice = ZkTecoAttlogParser.parse("OTHER", line, CAIRO).events().get(0);

		assertThat(first.dedupKey()).hasSize(64).isEqualTo(again.dedupKey());
		assertThat(first.dedupKey()).isNotEqualTo(checkOut.dedupKey()).isNotEqualTo(otherDevice.dedupKey());
	}
}
