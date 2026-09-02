package com.workin.devices.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.workin.devices.zkteco.ZkTecoAttlogParser;

class DevicePunchIngestionServiceTest {

	private static final ZoneId CAIRO = ZoneId.of("+02:00");

	@Test
	void aWallClockPunchBecomesTheInstantTheDevicesZoneSays() {
		assertThat(DevicePunchIngestionService.toUtc(LocalDateTime.of(2024, 7, 28, 8, 2, 11), CAIRO))
				.isEqualTo(LocalDateTime.of(2024, 7, 28, 6, 2, 11));
	}

	/**
	 * The round trip the epoch bug broke: a device reporting Unix seconds and
	 * one reporting the same moment as a wall clock must end with the same
	 * stored instant, and it must be the instant the device meant.
	 */
	@Test
	void bothTimestampFormsAgreeOnTheStoredInstant() {
		LocalDateTime fromEpoch = ZkTecoAttlogParser
				.parse("SN", "7\t1704096000\t0\t1", CAIRO).events().get(0).punchedAtLocal();
		LocalDateTime fromWallClock = ZkTecoAttlogParser
				.parse("SN", "7\t2024-01-01 10:00:00\t0\t1", CAIRO).events().get(0).punchedAtLocal();

		assertThat(fromEpoch).isEqualTo(fromWallClock);
		assertThat(DevicePunchIngestionService.toUtc(fromEpoch, CAIRO))
				.isEqualTo(LocalDateTime.of(2024, 1, 1, 8, 0, 0));
	}

	/**
	 * A wall clock inside a spring-forward gap does not exist. The punch is
	 * still recorded rather than refused -- see the method's own note -- and
	 * this pins that choice so it cannot change silently.
	 */
	@Test
	void aWallClockInsideADaylightSavingGapIsShiftedRatherThanRejected() {
		LocalDateTime inTheGap = LocalDateTime.of(2026, 4, 24, 0, 30);

		assertThat(DevicePunchIngestionService.toUtc(inTheGap, ZoneId.of("Africa/Cairo")))
				.isEqualTo(LocalDateTime.of(2026, 4, 23, 22, 30));
	}
}
