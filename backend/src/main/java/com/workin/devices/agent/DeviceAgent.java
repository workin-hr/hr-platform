package com.workin.devices.agent;

/**
 * One row of {@code device_agents}, without the token hash: nothing that reads
 * an agent needs it, and a record that never carries it cannot leak it into a
 * view or a log line.
 */
public record DeviceAgent(
		long id,
		long companyId,
		String name,
		String tokenHint,
		boolean active,
		String agentVersion,
		String lastSeenAt,
		String lastSeenIp,
		String lastReport,
		String createdAt) {
}
