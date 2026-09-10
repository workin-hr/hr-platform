package com.workin.devices.zkteco;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The three fields {@code POST /iclock/devicecmd} is defined to carry.
 *
 * <p>Exists so the receiver logs a parsed shape rather than raw body text. The
 * route is unauthenticated, so an unparsed body is arbitrary attacker-supplied
 * content going into production logs -- and a way around the biometric
 * filtering {@code OPERLOG} bodies get, since template data pasted here would
 * otherwise reach the log untouched.
 *
 * <p>Unknown keys are dropped rather than kept: nothing downstream reads them,
 * so keeping them would only preserve the problem under a tidier name.
 */
final class ZkTecoCommandResult {

	/** Only these, and only the first occurrence of each. */
	private static final java.util.Set<String> KNOWN = java.util.Set.of("ID", "Return", "CMD");

	private ZkTecoCommandResult() {
	}

	static Map<String, String> parse(String body) {
		Map<String, String> fields = new LinkedHashMap<>();
		if (body == null) {
			return fields;
		}
		// A terminal separates the fields with `&`, and firmware in the wild
		// also sends them one per line -- both are accepted because refusing
		// one would lose the diagnostic this log exists for.
		for (String token : body.split("[&\\r\\n]")) {
			int equals = token.indexOf('=');
			if (equals <= 0) {
				continue;
			}
			String key = token.substring(0, equals).strip();
			if (KNOWN.contains(key)) {
				fields.putIfAbsent(key, token.substring(equals + 1).strip());
			}
		}
		return fields;
	}
}
