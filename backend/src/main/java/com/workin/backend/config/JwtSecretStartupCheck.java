package com.workin.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Refuses to start if the old documentation placeholder JWT secret is
 * ever reused as a real runtime value. Missing secrets already fail
 * earlier via property resolution; this check closes the second failure
 * mode where a human explicitly sets the known placeholder string.
 */
@Component
public class JwtSecretStartupCheck implements ApplicationRunner {

	static final String PLACEHOLDER_SECRET = "dev-only-placeholder-secret-replace-before-any-real-deployment-000000";

	private final String jwtSecret;

	public JwtSecretStartupCheck(@Value("${app.jwt.secret}") String jwtSecret) {
		this.jwtSecret = jwtSecret;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (PLACEHOLDER_SECRET.equals(jwtSecret)) {
			throw new IllegalStateException(
					"Application JWT secret is still set to the known placeholder value. "
							+ "Refusing to start with a forgeable token-signing key.");
		}
	}

}
