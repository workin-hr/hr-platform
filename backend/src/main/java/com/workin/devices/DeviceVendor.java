package com.workin.devices;

/**
 * The vendors this module has an adapter for. The stored code is the value
 * {@code attendance_devices.vendor} holds and its CHECK constraint allows,
 * and the tag every device metric carries.
 */
public enum DeviceVendor {

	ZKTECO("zkteco");

	private final String code;

	DeviceVendor(String code) {
		this.code = code;
	}

	public String code() {
		return code;
	}
}
