package com.workin.devices.zkteco;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import com.workin.devices.registry.AttendanceDevice;

/**
 * The {@code GET /iclock/cdata?options=all} reply: the device's operating
 * parameters, plain text, one {@code key=value} per line. Documented in the
 * PUSH SDK protocol and agreed on by the independent implementations the
 * design cites; the hardware checklist confirms it against real firmware.
 *
 * <p>{@code TransFlag} is the biometric gate: {@code AttLog} and {@code OpLog}
 * only. Enrolment, fingerprint, face and photo transfer flags are absent on
 * purpose, and {@link ZkTecoOperlogFilter} discards anything that arrives
 * regardless.
 *
 * <p>An unclaimed device receives the same shape with zero stamps. Its
 * uploads are then refused with an error status, so the terminal keeps the
 * records and retries after {@code ErrorDelay}: nothing punched before the
 * claim is lost, and nothing is stored before it either.
 */
public final class ZkTecoHandshake {

	static final String CRLF = "\r\n";

	private ZkTecoHandshake() {
	}

	public static String response(String serialNumber, Optional<AttendanceDevice> device, Instant now) {
		String attlogStamp = device.map(AttendanceDevice::lastAttlogStamp).filter(s -> s != null && !s.isBlank()).orElse("0");
		int timeZoneHours = device.map(d -> offsetHours(d.deviceTimeZone(), now)).orElse(0);
		return "GET OPTION FROM: " + serialNumber + CRLF
				+ "ATTLOGStamp=" + attlogStamp + CRLF
				+ "OPERLOGStamp=0" + CRLF
				+ "ATTPHOTOStamp=0" + CRLF
				+ "ErrorDelay=30" + CRLF
				+ "Delay=10" + CRLF
				+ "TransTimes=00:00;14:05" + CRLF
				+ "TransInterval=1" + CRLF
				+ "TransFlag=TransData AttLog\tOpLog" + CRLF
				+ "TimeZone=" + timeZoneHours + CRLF
				+ "Realtime=1" + CRLF
				+ "Encrypt=None" + CRLF;
	}

	static int offsetHours(String zone, Instant now) {
		return ZoneId.of(zone).getRules().getOffset(now).getTotalSeconds() / 3600;
	}
}
