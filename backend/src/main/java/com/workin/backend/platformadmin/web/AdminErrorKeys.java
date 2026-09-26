package com.workin.backend.platformadmin.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Keeps a page's {@code errorKey} to the messages the dashboard actually has
 * (D-289).
 *
 * <p>Twenty-odd controllers pass their {@code ?error=} parameter straight into
 * the model, and the template renders it through {@code t}, which answers an
 * unknown key with the key itself. So any link could make a genuine admin page
 * display text of the sender's choosing inside its own error banner -- escaped,
 * so not script, but a convincing place to tell an administrator to do
 * something. Here, after every handler under {@code /admin}, a key that is not
 * in {@code i18n/admin-messages} or {@code i18n/admin-own} is dropped and the
 * banner renders nothing. One place rather than one call per controller,
 * because a controller added later would not remember to make the call.
 *
 * <p>The base files are the whole key set: a translation only ever adds a
 * value for a key the base already has, and a key missing from the base falls
 * back to the key itself, which is the case being refused.
 */
@Configuration
class AdminErrorKeys implements WebMvcConfigurer, HandlerInterceptor {

	static final String ATTRIBUTE = "errorKey";

	private final Set<String> known = load("i18n/admin-messages.properties", "i18n/admin-own.properties");

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(this).addPathPatterns(
				PlatformAdminWebSecurityConfig.PATH_PREFIX, PlatformAdminWebSecurityConfig.PATH_PREFIX + "/**");
	}

	@Override
	public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
			@Nullable ModelAndView modelAndView) {
		if (modelAndView != null && modelAndView.getModel().get(ATTRIBUTE) instanceof String key
				&& !isKnown(key)) {
			modelAndView.getModel().put(ATTRIBUTE, null);
		}
	}

	boolean isKnown(String key) {
		return this.known.contains(key);
	}

	private static Set<String> load(String... resources) {
		Set<String> keys = new HashSet<>();
		for (String resource : resources) {
			try (InputStream in = AdminErrorKeys.class.getClassLoader().getResourceAsStream(resource)) {
				if (in == null) {
					throw new IllegalStateException("admin message bundle missing: " + resource);
				}
				Properties properties = new Properties();
				properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
				keys.addAll(properties.stringPropertyNames());
			} catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}
		return Set.copyOf(keys);
	}
}
