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
 * <p>Reading the mapping also handles the case a boolean cannot express.
 * Several pages are backed by tables that exist only in the legacy MySQL
 * schema, so their controllers are {@code @Profile("phase1-mysql")}. The
 * same build serves a different set of pages depending on the profile,
 * and this reports whichever set is really there.
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
