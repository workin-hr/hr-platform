package com.workin.backend.platformadmin.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.ui.Model;

/**
 * Puts the CSRF token where a JTE template can read it.
 *
 * <p>JTE has no form taglib, so the token is an explicit template parameter
 * rather than something a tag emits. Making it explicit is the point: a form
 * that forgets it fails visibly on its first POST rather than silently losing
 * its protection.
 */
final class PlatformAdminWebCsrf {

	private PlatformAdminWebCsrf() {
	}

	static void expose(Model model, HttpServletRequest request) {
		CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
		model.addAttribute("csrfParameterName", token != null ? token.getParameterName() : "_csrf");
		model.addAttribute("csrfToken", token != null ? token.getToken() : "");
	}

}
