package com.workin.devices;

/**
 * How a punch reached the platform -- {@code device_punches.delivered_via}.
 *
 * <p>Not the vendor. One ZKTeco terminal can push over ADMS and be read by an
 * on-premises agent at the same time; the dedup key collapses the two copies,
 * and this records which one arrived first.
 */
public enum DeviceDelivery {

	/** The terminal sent it itself (ZKTeco ADMS). */
	PUSH,

	/** An on-premises agent read it from the terminal over its LAN protocol. */
	AGENT,

	/** An operator imported a file the terminal exported, usually from a USB stick. */
	FILE
}
