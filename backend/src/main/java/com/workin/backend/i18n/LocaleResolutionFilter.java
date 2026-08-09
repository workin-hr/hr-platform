package com.workin.backend.i18n;

import java.io.IOException;
import java.util.Locale;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the request language once (owner decision 2026-08-08,
 * legacy app_locale() precedence): explicit ?lang wins, then
 * Accept-Language, default English. Runs before the security chain so
 * even rejected requests render localized bodies.
 *
 * <p>The resolved value is also stashed as a request attribute
 * ({@link #RESOLVED_LOCALE_ATTRIBUTE}) for {@link LocaleResolverConfig}
 * to read: DispatcherServlet unconditionally rebuilds
 * LocaleContextHolder from its own LocaleResolver bean at the top of
 * every request, so that bean cannot itself read back through
 * LocaleContextHolder (self-referential -- StackOverflowError) and
 * needs a plain, non-ThreadLocal source of truth instead.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocaleResolutionFilter extends OncePerRequestFilter {

	public static final String RESOLVED_LOCALE_ATTRIBUTE = LocaleResolutionFilter.class.getName() + ".RESOLVED_LOCALE";

	@Override
	protected void doFilterInternal(
			HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String langParam = request.getParameter("lang");
		Locale locale = langParam != null && !langParam.isBlank()
				? SupportedLocales.fromLangParam(langParam)
				: SupportedLocales.fromAcceptLanguage(request.getHeader("Accept-Language"));
		request.setAttribute(RESOLVED_LOCALE_ATTRIBUTE, locale);
		LocaleContextHolder.setLocale(locale);
		try {
			filterChain.doFilter(request, response);
		} finally {
			LocaleContextHolder.resetLocaleContext();
		}
	}

}
