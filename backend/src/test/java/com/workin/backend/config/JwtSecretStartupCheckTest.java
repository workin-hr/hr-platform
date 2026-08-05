package com.workin.backend.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

class JwtSecretStartupCheckTest {

	@Test
	void failsLoudlyWhenThePlaceholderSecretIsUsed() {
		var check = new JwtSecretStartupCheck(JwtSecretStartupCheck.PLACEHOLDER_SECRET);

		assertThatThrownBy(() -> check.run(null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("placeholder");
	}

	@Test
	void passesWhenARealSecretIsConfigured() {
		var check = new JwtSecretStartupCheck("real-secret-value-not-the-placeholder-000000000000");

		assertDoesNotThrow(() -> check.run(null));
	}

}
