package com.workin.legacy.notifications;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The temporary stand-in for legacy's {@code sendPushToEmployee()}: it delivers
 * nothing, and says so.
 *
 * <p><b>This does not satisfy Phase 1's push-delivery requirement.</b> Real
 * Firebase delivery is <b>hr-platform#22</b>, a cross-cutting Phase 1 exit
 * requirement and a hard release/cutover blocker, deliberately kept out of the
 * Wave 12.4 employee PR so that module does not turn into Firebase
 * infrastructure work. What Wave 12.4 owes and delivers is the call boundary and
 * its ordering -- notification row first, delivery attempt second, failure
 * swallowed -- so #22 replaces this class and changes nothing else.
 *
 * <p>It logs rather than staying silent: a notification that never reached a
 * device is worth a line in the log until #22 lands, and the log is the only
 * signal that the gap is real in a running system. It deliberately does not
 * throw -- callers already swallow, so throwing would only add noise.
 */
@Component
public class LegacyPushDeliveryUnavailable implements LegacyPushDelivery {

	private static final Logger LOG = LoggerFactory.getLogger(LegacyPushDeliveryUnavailable.class);

	@Override
	public void sendToEmployee(long employeeId, String title, String body, Map<String, String> data) {
		LOG.info(
				"push delivery is not implemented (hr-platform#22): notification {} of type {} for employee {} "
				+ "was stored but not pushed",
				data.get("notification_id"), data.get("notification_type"), employeeId);
	}

}
