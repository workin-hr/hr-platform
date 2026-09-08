package com.workin.backend.platformadmin.web;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Which admin pages this deployment can actually serve, read from the
 * handler mapping rather than declared by hand.
 *
 * <p>{@link AdminNav} lists every page the PHP dashboard has, including
 * the ones still to be ported. Marking availability with a boolean on the
 * item would be a second copy of the truth, and the copies drift in a
 * specific direction: an item is marked implemented, the controller is
 * later profile-scoped or removed, and the sidebar keeps offering a link
 * that 404s.
 *
 * <p>Reading the mapping rather than declaring a list also means a page
 * that is removed, renamed or conditionally registered is reported as it
 * really is, not as a list remembers it. (It used to matter more: several
 * pages existed under one Spring profile and not the other. There is one
 * profile's worth of pages now -- ADR-0017 -- and the mechanism stays because
 * it is still the honest one.)
 */
@Component
public class AdminPageAvailability {

	private final Set<String> pages;

	public AdminPageAvailability(
			@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping) {
		this.pages = handlerMapping.getHandlerMethods().keySet().stream()
				.flatMap(info -> patternsOf(info).stream())
				.map(AdminPageAvailability::pageOf)
				.filter(page -> !page.isEmpty())
				.collect(Collectors.toUnmodifiableSet());
	}

	private static Set<String> patternsOf(RequestMappingInfo info) {
		return info.getPathPatternsCondition() == null
				? Set.of()
				: info.getPathPatternsCondition().getPatternValues();
	}

	/**
	 * {@code /admin/faqs} and {@code /admin/faqs/items} both mean the
	 * {@code faqs} page; {@code /admin} itself is the dashboard's
	 * {@code index}. Anything outside {@code /admin} is not a page.
	 */
	private static String pageOf(String pattern) {
		if (!pattern.startsWith("/admin")) {
			return "";
		}
		String rest = pattern.substring("/admin".length());
		if (rest.isEmpty() || rest.equals("/")) {
			return "index";
		}
		String[] segments = rest.substring(1).split("/");
		return segments[0].startsWith("{") ? "" : segments[0];
	}

	public boolean has(String page) {
		return this.pages.contains(page);
	}

}
