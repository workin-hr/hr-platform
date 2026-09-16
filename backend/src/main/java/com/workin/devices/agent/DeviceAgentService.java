package com.workin.devices.agent;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.workin.devices.DeviceInput;
import com.workin.legacy.LegacyClock;

/**
 * Issuing, listing and revoking on-premises agents, and turning a presented
 * token back into one.
 *
 * <p>Always on, unlike the endpoint agents submit to: an administrator has to
 * be able to issue a token before a deployment turns agent ingestion on, and
 * revoke one after it is turned off.
 */
@Service
public class DeviceAgentService {

	static final int MAX_NAME = 100;

	/** @param token the bearer token, shown once; only its hash was stored */
	public record IssuedAgent(DeviceAgent agent, String token) {
	}

	private final DeviceAgentStore agents;
	private final LegacyClock clock;

	public DeviceAgentService(DeviceAgentStore agents, LegacyClock clock) {
		this.agents = agents;
		this.clock = clock;
	}

	/**
	 * The caller has already established that {@code companyId} exists and that
	 * it may act for it; this only refuses a name that cannot be stored.
	 */
	public IssuedAgent issue(long companyId, String name) {
		String bounded = DeviceInput.bounded(name, MAX_NAME);
		if (bounded == null) {
			throw new IllegalArgumentException("an agent needs a name");
		}
		DeviceAgentTokens.Issued issued = DeviceAgentTokens.issue();
		long id = agents.create(companyId, bounded, issued.sha256(), issued.hint(), clock.now());
		return new IssuedAgent(agents.find(id).orElseThrow(), issued.token());
	}

	/** A malformed token is refused before it costs a query. */
	public Optional<DeviceAgent> authenticate(String token) {
		if (!DeviceAgentTokens.isWellFormed(token)) {
			return Optional.empty();
		}
		return agents.findActiveByTokenSha256(DeviceAgentTokens.sha256(token));
	}

	public List<DeviceAgent> list(Long companyId) {
		return agents.list(companyId);
	}

	public Optional<DeviceAgent> find(long id) {
		return agents.find(id);
	}

	/** @return false when no such agent exists */
	public boolean setActive(long id, boolean active) {
		return agents.setActive(id, active, clock.now());
	}
}
