package com.workin.backend.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.workin.backend.i18n.ApiErrorBody;
import com.workin.backend.i18n.MessageKeys;
import com.workin.backend.i18n.Messages;

import tools.jackson.databind.ObjectMapper;

/**
 * Puts Spring Security's own rejections on the {@code {code, message}}
 * contract (issue #70).
 *
 * <p>Without this, a missing or expired access token never reaches
 * {@code ApiExceptionHandler}: Spring Security rejects it with
 * {@code response.sendError(...)}, which triggers a servlet ERROR
 * dispatch to {@code /error} and is answered by Spring Boot's
 * {@code BasicErrorController} with its default
 * {@code {timestamp, status, error, path}} body. Access tokens live 15
 * minutes, so that off-contract shape was the single most frequent
 * error response the clients would ever see.
 *
 * <p>Registering an entry point changes the mechanism, not just the
 * body: Spring Security writes the response here directly instead of
 * calling {@code sendError}, so there is no ERROR dispatch at all. The
 * {@code /error} permit stays for the separate case it was added for —
 * a {@code ResponseStatusException} thrown from inside a controller.
 *
 * <p>The bodies are deliberately generic. A caller learns that it is
 * unauthenticated or forbidden and nothing else — no permission name,
 * no hint whether the resource exists — matching
 * {@code AuthorizationPolicyInterceptor}'s existing 403 and the uniform
 * 404 rule. Localization works because {@code LocaleResolutionFilter}
 * runs at highest precedence, ahead of the security chain, which is
 * exactly why it was ordered that way.
 */
@Component
public class ApiSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final ObjectMapper objectMapper;
	private final Messages messages;

	public ApiSecurityErrorHandler(ObjectMapper objectMapper, Messages messages) {
		this.objectMapper = objectMapper;
		this.messages = messages;
	}

	/** No credentials, or credentials that did not authenticate. */
	@Override
	public void commence(
			HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
			throws IOException {
		write(response, HttpServletResponse.SC_UNAUTHORIZED, MessageKeys.ERROR_UNAUTHORIZED);
	}

	/** Authenticated, but not allowed through. */
	@Override
	public void handle(
			HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
			throws IOException {
		write(response, HttpServletResponse.SC_FORBIDDEN, MessageKeys.ERROR_FORBIDDEN);
	}

	private void write(HttpServletResponse response, int status, String messageKey) throws IOException {
		if (response.isCommitted()) {
			return;
		}
		response.setStatus(status);
		response.setContentType("application/json;charset=UTF-8");
		response.getWriter().write(
				objectMapper.writeValueAsString(new ApiErrorBody(messageKey, messages.get(messageKey))));
	}

}
