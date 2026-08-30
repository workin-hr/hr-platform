package com.workin.legacy.auth.otp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * {@code otp_client_ip()} and {@code otp_client_user_agent()}.
 *
 * <p>The literal parser gets its own test because it is hand-rolled: the
 * obvious implementation, {@code InetAddress.getByName()}, resolves hostnames
 * and would let an unauthenticated caller drive DNS lookups through
 * {@code X-Forwarded-For}. A parser written to avoid that has to be shown to
 * still accept real addresses.
 */
class LegacyClientAddressTest {

	@Test
	void aHostnameOfHexLettersAndDotsIsNotAnAddress() {
		// The case that motivated the parser: every character is a valid hex
		// digit or a dot, so a character screen alone accepts it, and
		// InetAddress.getByName() would try to resolve it.
		assertThat(clientIp("bad.cafe")).isEmpty();
		assertThat(clientIp("dead.beef")).isEmpty();
		assertThat(clientIp("example.com")).isEmpty();
	}

	@Test
	void realIpv4LiteralsAreAccepted() {
		assertThat(clientIp("203.0.113.7")).isEqualTo("203.0.113.7");
		assertThat(clientIp("0.0.0.0")).isEqualTo("0.0.0.0");
		assertThat(clientIp("255.255.255.255")).isEqualTo("255.255.255.255");
	}

	@Test
	void malformedIpv4IsRejected() {
		assertThat(clientIp("256.0.0.1")).isEmpty();
		assertThat(clientIp("1.2.3")).isEmpty();
		assertThat(clientIp("1.2.3.4.5")).isEmpty();
		assertThat(clientIp("1.2.3.")).isEmpty();
		// FILTER_VALIDATE_IP rejects a zero-padded octet.
		assertThat(clientIp("01.2.3.4")).isEmpty();
	}

	@Test
	void realIpv6LiteralsAreAccepted() {
		assertThat(clientIp("2001:db8:85a3:0:0:8a2e:370:7334"))
				.isEqualTo("2001:db8:85a3:0:0:8a2e:370:7334");
		assertThat(clientIp("2001:db8::8a2e:370:7334")).isEqualTo("2001:db8::8a2e:370:7334");
		assertThat(clientIp("::1")).isEqualTo("::1");
		assertThat(clientIp("::")).isEqualTo("::");
		assertThat(clientIp("::ffff:203.0.113.7")).isEqualTo("::ffff:203.0.113.7");
	}

	@Test
	void malformedIpv6IsRejected() {
		assertThat(clientIp("2001:db8::8a2e::7334")).as("two compressions").isEmpty();
		assertThat(clientIp("2001:db8:85a3:0:0:8a2e:370")).as("only seven groups").isEmpty();
		assertThat(clientIp("2001:db8:85a3:0:0:8a2e:370:7334:1")).as("nine groups").isEmpty();
		assertThat(clientIp("2001:db8:85a3:0:0:8a2e:370:zzzz")).as("not hex").isEmpty();
		assertThat(clientIp("::ffff:999.0.113.7")).as("bad embedded IPv4").isEmpty();
	}

	/**
	 * An IPv4 tail is only legal at the very end of the literal. When the
	 * address ends in {@code ::} the tail after the compression is empty, so a
	 * dotted group in the head is the last one <em>parsed</em> without being
	 * last in the address. {@code inet_pton} -- and therefore
	 * {@code filter_var(FILTER_VALIDATE_IP)} -- rejects every one of these.
	 *
	 * <p>It matters beyond validation: an accepted value is stored and
	 * rate-limited as the client identity on deployments where the OTP IP
	 * columns exist, so a malformed forwarding header would take the place of
	 * the socket address it should have fallen through to.
	 */
	@Test
	void anIpv4TailIsRejectedAnywhereButTheEndOfTheLiteral() {
		assertThat(clientIp("1.2.3.4::")).as("dotted head, empty tail").isEmpty();
		assertThat(clientIp("1:1.2.3.4::")).as("dotted group before the compression").isEmpty();
		assertThat(clientIp("1.2.3.4::5")).as("dotted head with a non-empty tail").isEmpty();
		assertThat(clientIp("::1.2.3.4:5")).as("dotted group followed by another").isEmpty();

		assertThat(clientIp("::1.2.3.4")).as("still legal at the end").isEqualTo("::1.2.3.4");
		assertThat(clientIp("1:2::3:1.2.3.4")).as("and after a compression")
				.isEqualTo("1:2::3:1.2.3.4");
	}

	@Test
	void theHeaderOrderIsCloudflareThenForwardedThenRealIpThenTheSocket() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("10.0.0.1");
		assertThat(LegacyClientAddress.clientIp(request)).isEqualTo("10.0.0.1");

		request.addHeader("X-Real-IP", "203.0.113.9");
		assertThat(LegacyClientAddress.clientIp(request)).isEqualTo("203.0.113.9");

		request.addHeader("X-Forwarded-For", "203.0.113.8, 70.41.3.18");
		assertThat(LegacyClientAddress.clientIp(request))
				.as("the first entry of the list wins")
				.isEqualTo("203.0.113.8");

		request.addHeader("CF-Connecting-IP", "203.0.113.5");
		assertThat(LegacyClientAddress.clientIp(request)).isEqualTo("203.0.113.5");
	}

	@Test
	void anUnparseableHeaderFallsThroughToTheNextCandidate() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("10.0.0.1");
		request.addHeader("CF-Connecting-IP", "not-an-ip");
		assertThat(LegacyClientAddress.clientIp(request))
				.as("skipped, not fatal")
				.isEqualTo("10.0.0.1");
	}

	@Test
	void theUserAgentIsTruncatedAtFiveHundredAndTwelveCharacters() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("User-Agent", "أ".repeat(600));
		// mb_substr() counts characters, not bytes.
		assertThat(LegacyClientAddress.userAgent(request)).hasSize(512);
	}

	private static String clientIp(String header) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("CF-Connecting-IP", header);
		// No remote address, so an accepted header is the only possible answer.
		request.setRemoteAddr(null);
		return LegacyClientAddress.clientIp(request);
	}
}
