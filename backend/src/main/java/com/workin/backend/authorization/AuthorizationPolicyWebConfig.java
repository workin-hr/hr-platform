package com.workin.backend.authorization;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link AuthorizationPolicyInterceptor} for every route --
 * the interceptor itself is a no-op for handlers without
 * {@link RequiresPermission}, so blanket registration is what makes
 * the gate impossible to forget per endpoint (hr-legacy#8's failure
 * mode was exactly per-endpoint opt-in).
 */
@Configuration
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
