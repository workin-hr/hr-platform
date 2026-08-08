package com.workin.backend.i18n;

import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.servlet.LocaleResolver;

/**
 * DispatcherServlet rebuilds LocaleContextHolder from its own
 * LocaleResolver bean at the start of every request
 * (FrameworkServlet#processRequest -&gt; buildLocaleContext -&gt;
 * initContextHolders), unconditionally overwriting whatever
 * LocaleResolutionFilter already set. Left unregistered, Spring falls
 * back to AcceptHeaderLocaleResolver, which ignores ?lang entirely and
 * clobbers the filter's param -&gt; header -&gt; English precedence with a
 * bare header (or container-default) read. This bean makes
 * DispatcherServlet echo the locale the filter already resolved.
 *
 * <p>Deliberately reads the request attribute the filter stashed
 * ({@link LocaleResolutionFilter#RESOLVED_LOCALE_ATTRIBUTE}), not
 * LocaleContextHolder: DispatcherServlet wraps this resolver's call in
 * a lazy LocaleContext and installs it into LocaleContextHolder
 * <em>before</em> invoking it, so reading LocaleContextHolder here
 * would call back into this very resolver -- StackOverflowError.
 */
@Configuration
public class LocaleResolverConfig {

	@Bean
	public LocaleResolver localeResolver() {
		return new LocaleResolver() {

			@Override
			public Locale resolveLocale(HttpServletRequest request) {
				Object resolved = request.getAttribute(LocaleResolutionFilter.RESOLVED_LOCALE_ATTRIBUTE);
				return resolved instanceof Locale locale ? locale : SupportedLocales.DEFAULT;
			}

			@Override
			public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
				request.setAttribute(LocaleResolutionFilter.RESOLVED_LOCALE_ATTRIBUTE, locale);
				LocaleContextHolder.setLocale(locale);
			}

		};
	}

}
