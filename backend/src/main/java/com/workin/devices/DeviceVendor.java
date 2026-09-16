package com.workin.devices;

/**
 * The vendors this module has an adapter for. The stored code is the value
 * {@code attendance_devices.vendor} holds and its CHECK constraint allows,
 * and the tag every device metric carries.
 */
public enum DeviceVendor {

	ZKTECO("zkteco"),

	/** Pulled over ISAPI by an on-premises agent; it has no push adapter here. */
	HIKVISION("hikvision");

	private final String code;

	DeviceVendor(String code) {
		this.code = code;
	}

	public String code() {
		return code;
	}

	/** The vendor a stored or submitted code names, if it names one. */
	public static java.util.Optional<DeviceVendor> fromCode(String code) {
		for (DeviceVendor vendor : values()) {
			if (vendor.code.equals(code)) {
				return java.util.Optional.of(vendor);
			}
		}
		return java.util.Optional.empty();
	}
}
