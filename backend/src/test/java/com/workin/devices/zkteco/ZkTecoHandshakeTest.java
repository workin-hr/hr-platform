package com.workin.devices.zkteco;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.workin.devices.registry.AttendanceDevice;

class ZkTecoHandshakeTest {

	private static AttendanceDevice device(String zone, String stamp) {
		return new AttendanceDevice(7L, 9501L, 9511L, "zkteco", "SN1", "Gate", null, null, null, zone, true,
				null, null, stamp, null, "2026-09-02 10:00:00", "2026-09-02 10:00:00");
	}

	/**
	 * The stamp a device sent is never returned to it: an unauthenticated
	 * caller could otherwise set the bookmark past records the terminal still
	 * holds, and it would drop them.
	 */
	@Test
	void aClaimedDeviceIsAlwaysAskedToResendAndGetsOnlyTheTwoAllowedTransferFlags() {
		String response = ZkTecoHandshake.response("SN1", Optional.of(device("+03:00", "4711")), Instant.parse("2026-09-02T08:00:00Z"));

		assertThat(response).startsWith("GET OPTION FROM: SN1\r\n");
		assertThat(response).contains("ATTLOGStamp=0\r\n").doesNotContain("4711");
		assertThat(response).contains("TimeZone=3\r\n").contains("Realtime=1\r\n");
		assertThat(response).contains("TransFlag=TransData AttLog\tOpLog\r\n");
		assertThat(response).doesNotContain("EnrollFP").doesNotContain("ChgFP").doesNotContain("EnrollUser")
				.doesNotContain("UserPic").doesNotContain("Face").doesNotContain("BioData");
	}

	@Test
	void anUnclaimedDeviceGetsAValidReplyWithZeroStamps() {
		String response = ZkTecoHandshake.response("NEW", Optional.empty(), Instant.parse("2026-09-02T08:00:00Z"));

		assertThat(response).startsWith("GET OPTION FROM: NEW\r\n").contains("ATTLOGStamp=0\r\n").contains("TimeZone=0\r\n");
	}

	@Test
	void anIanaZoneResolvesToItsOffsetAtThatInstant() {
		assertThat(ZkTecoHandshake.offsetHours("Asia/Riyadh", Instant.parse("2026-09-02T08:00:00Z"))).isEqualTo(3);
		assertThat(ZkTecoHandshake.offsetHours("+02:00", Instant.parse("2026-01-02T08:00:00Z"))).isEqualTo(2);
	}
}
