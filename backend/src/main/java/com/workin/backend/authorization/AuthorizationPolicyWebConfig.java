package com.workin.backend.authorization;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link AuthorizationPolicyInterceptor} for every route --
 * the interceptor itself is a no-op for handlers without
 * {@link RequiresPermission}, so blanket registration is what makes
 * the gate impossible to forget per endpoint (hr-legacy#8's failure
 * mode was exactly per-endpoint opt-in).
 *
 * <p>{@code @Profile("!phase1-mysql")}: depends on
 * {@link AuthorizationPolicyInterceptor}, itself Postgres-only per
 * ADR-0013 -- {@code hr_permissions} authorization mapping does not
 * exist for the legacy contract yet (Phase 1 punch-list item #11).
 */
@Configuration
@Profile("!phase1-mysql")
public class AuthorizationPolicyWebConfig implements WebMvcConfigurer {

	private final AuthorizationPolicyInterceptor authorizationPolicyInterceptor;

	public AuthorizationPolicyWebConfig(AuthorizationPolicyInterceptor authorizationPolicyInterceptor) {
		this.authorizationPolicyInterceptor = authorizationPolicyInterceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(authorizationPolicyInterceptor);
	}

}
