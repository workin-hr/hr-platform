package com.workin.devices.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class DeviceAgentTokensTest {

	@Test
	void anIssuedTokenIsWellFormedAndOnlyItsDigestAndLastFourCharactersAreKept() {
		DeviceAgentTokens.Issued issued = DeviceAgentTokens.issue();

		assertThat(DeviceAgentTokens.isWellFormed(issued.token())).isTrue();
		assertThat(issued.token()).startsWith("wda_").hasSize(47);
		assertThat(issued.sha256()).hasSize(64).isEqualTo(DeviceAgentTokens.sha256(issued.token()))
				.doesNotContain(issued.token());
		assertThat(issued.hint()).isEqualTo(issued.token().substring(43));
	}

	@Test
	void tokensDoNotRepeat() {
		Set<String> seen = new HashSet<>();
		for (int index = 0; index < 1000; index++) {
			assertThat(seen.add(DeviceAgentTokens.issue().token())).isTrue();
		}
	}

	@Test
	void anythingThatIsNotATokenIsRefusedBeforeItIsHashed() {
		assertThat(DeviceAgentTokens.isWellFormed(null)).isFalse();
		assertThat(DeviceAgentTokens.isWellFormed("")).isFalse();
		assertThat(DeviceAgentTokens.isWellFormed("wda_short")).isFalse();
		assertThat(DeviceAgentTokens.isWellFormed("xyz_" + "A".repeat(43))).isFalse();
		assertThat(DeviceAgentTokens.isWellFormed("wda_" + "A".repeat(42) + "\n")).isFalse();
		assertThat(DeviceAgentTokens.isWellFormed("wda_" + "A".repeat(44))).isFalse();
	}
}
