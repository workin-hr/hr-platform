package com.workin.devices.zkteco;

import java.util.LinkedHashMap;
import java.util.Map;

import com.workin.devices.DeviceInput;

/**
 * {@code POST /iclock/cdata?table=options}: the device describing itself as
 * {@code key=value} pairs separated by commas or line breaks
 * ({@code ~DeviceName}, {@code FirmVer}, {@code PushVersion}, ...). Only
 * three keys are read; the rest are ignored, not stored.
 */
public final class ZkTecoOptionsUpload {

	/** The model/firmware columns are VARCHAR(100). */
	private static final int MAX_VALUE = 100;

	private ZkTecoOptionsUpload() {
	}

	public record SelfDescription(String model, String firmware, String pushVersion) {
	}

	public static SelfDescription parse(String body) {
		Map<String, String> pairs = new LinkedHashMap<>();
		for (String part : body.split("[,\\r\\n]+")) {
			int eq = part.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			pairs.put(part.substring(0, eq).strip(), DeviceInput.bounded(part.substring(eq + 1), MAX_VALUE));
		}
		return new SelfDescription(
				pairs.get("~DeviceName"), pairs.get("FirmVer"), pairs.get("PushVersion"));
	}

}
