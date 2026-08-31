package com.workin.legacy.wire;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.ObjectMapper;

/**
 * Ports {@code apis/api/index.php}'s router: a suffix-less route resolves to
 * the {@code .php} file that serves it.
 *
 * <h2>Why this exists</h2>
 * <p>Every legacy controller here maps a <em>file</em> path
 * ({@code /apis/api/configs/get.php}), because the endpoint inventory was built
 * from the PHP source tree, where those files are what you see. But that is not
 * the URL any client uses. {@code apis/.htaccess} rewrites only when the target
 * does <em>not</em> exist on disk, so a request for a real {@code .php} file is
 * served directly -- and those files assume {@code helpers/functions.php} is
 * already loaded, which only {@code index.php} does. Requesting one directly is
 * a fatal error, not an endpoint.
 *
 * <p>Confirmed against production on 2026-08-31:
 * {@code GET /apis/api/configs/get} answers <b>200</b>, and
 * {@code GET /apis/api/configs/get.php} answers <b>500</b>. The suffix-less
 * route is the contract; the {@code .php} path is an accident of the file
 * layout. Both Flutter clients agree -- {@code api_constants.dart} joins
 * {@code https://workin.company/apis/api/} with paths like
 * {@code auth/login_employee}, and not one of its 266 endpoint constants ends
 * in {@code .php}.
 *
 * <p>Without this filter Java answers the client's URL form for 9 of the 190
 * endpoints the clients call, and 404s or 401s the rest -- measured, not
 * estimated. That is a total break of D-111's zero-client-change premise, at
 * the routing layer, before any business logic runs.
 *
 * <h2>Why a filter, and not 190 more mappings</h2>
 * <p>This is what PHP itself does: {@code index.php} takes
 * {@code /api/{module}/{action}} and includes {@code api/{module}/{action}.php}.
 * Reproducing that as one rewrite keeps a single mapping per endpoint, leaves
 * the security matchers in {@link LegacyPhpRoutes} (38 of which carry
 * {@code .php}) matching exactly as before, and means a new endpoint cannot be
 * added in one form and forgotten in the other.
 *
 * <p>The request is <em>wrapped</em> rather than forwarded, so Spring Security
 * and the dispatcher both observe the rewritten path. That ordering matters:
 * the permit-list is keyed on {@code .php} paths, so a rewrite happening after
 * the security chain would authenticate the wrong path and reject public
 * routes.
 */
public class LegacyPhpRouterFilter extends OncePerRequestFilter {

	private static final String API_PREFIX = "/apis/api/";
	private static final String PHP_SUFFIX = ".php";

	private final Supplier<Set<String>> mappedRoutes;
	private final LegacyMessages messages;
	private final ObjectMapper objectMapper;

	public LegacyPhpRouterFilter(
			Supplier<Set<String>> mappedRoutes, LegacyMessages messages, ObjectMapper objectMapper) {
		this.mappedRoutes = mappedRoutes;
		this.messages = messages;
		this.objectMapper = objectMapper;
	}

	/**
	 * {@code index.php} lowercases each segment and strips everything outside
	 * {@code [a-z0-9_]} before matching. The consequence is easy to miss: a
	 * request for a <em>non-existent</em> {@code .php} file is rewritten to the
	 * router by {@code .htaccess}, and the router then reads the dot out of the
	 * segment -- so {@code /apis/api/configs/nope.php} looks for action
	 * {@code nopephp}, and reports that name back in the 501.
	 */
	private static String phpSegment(String raw) {
		StringBuilder out = new StringBuilder(raw.length());
		for (char c : raw.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
				out.append(c);
			}
		}
		return out.toString();
	}

	/**
	 * The file a legacy route resolves to, or {@code null} if the path is not
	 * one legacy would route.
	 *
	 * <p>{@code index.php} reads the two segments after {@code api} and
	 * <b>ignores the rest</b>, so {@code /apis/api/phone_countries/list/extra}
	 * serves the same endpoint as {@code /apis/api/phone_countries/list}.
	 * Verified against the running PHP: both answer 200, as does
	 * {@code .../list/a/b}.
	 *
	 * <p>An earlier version rejected deeper paths and shipped a test asserting
	 * that rejection — documenting the opposite of what legacy does, on the
	 * strength of this class's own javadoc saying the remainder is ignored.
	 * A client appending anything to a route would have received a 401 or 404
	 * from Java where PHP serves the endpoint.
	 */
	private static String resolveLegacyFile(String path) {
		if (!path.startsWith(API_PREFIX) || path.endsWith(PHP_SUFFIX)) {
			return null;
		}
		String remainder = path.substring(API_PREFIX.length());
		int slash = remainder.indexOf('/');
		if (slash <= 0 || slash == remainder.length() - 1) {
			return null;
		}
		int next = remainder.indexOf('/', slash + 1);
		String moduleAndAction = next < 0 ? remainder : remainder.substring(0, next);
		return API_PREFIX + moduleAndAction + PHP_SUFFIX;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String uri = request.getRequestURI();
		String rewritten = resolveLegacyFile(uri);
		String target = rewritten == null ? uri : rewritten;

		// Only paths nothing serves take the router's error branch. A mapped
		// route -- in either form -- is dispatched exactly as before, so this
		// cannot change the behaviour of a delivered endpoint.
		if (uri.startsWith(API_PREFIX) && !mappedRoutes.get().contains(target)) {
			writeRouterRefusal(request, response, uri);
			return;
		}
		if (rewritten == null) {
			filterChain.doFilter(request, response);
			return;
		}
		filterChain.doFilter(new HttpServletRequestWrapper(request) {
			@Override
			public String getRequestURI() {
				return rewritten;
			}

			@Override
			public StringBuffer getRequestURL() {
				String original = ((HttpServletRequest) getRequest()).getRequestURL().toString();
				// Rebuild from the rewritten path rather than appending: extra
				// segments are dropped, so the suffix is not simply added.
				int pathStart = original.indexOf(uri);
				return new StringBuffer(
						pathStart < 0 ? original : original.substring(0, pathStart) + rewritten);
			}

			@Override
			public String getServletPath() {
				return rewritten;
			}

			@Override
			public String getPathInfo() {
				return null;
			}
		}, response);
	}

	/**
	 * {@code index.php}'s three refusals, for a path no endpoint serves.
	 *
	 * <p>These run <b>before</b> authentication, which is the half that is easy
	 * to lose. PHP resolves the module against the allow-list at the top of
	 * {@code index.php}; {@code requireAuth()} lives inside the action file and
	 * never executes for a path that does not resolve. Java's security chain
	 * sits in front of the dispatcher, so before this filter an unauthenticated
	 * request for an unknown path answered <b>401</b> where PHP answers
	 * <b>404</b> -- and {@code time/now}, which the mobile client calls from its
	 * home screen, is exactly that path.
	 *
	 * <p>Written straight to the response rather than thrown: this filter is
	 * registered outside the {@code DispatcherServlet}, so
	 * {@link LegacyWireExceptionHandler} -- a {@code @RestControllerAdvice} --
	 * cannot see an exception raised here.
	 */
	private void writeRouterRefusal(HttpServletRequest request, HttpServletResponse response, String uri)
			throws IOException {
		String remainder = uri.substring(API_PREFIX.length());
		int slash = remainder.indexOf('/');
		String module = phpSegment(slash < 0 ? remainder : remainder.substring(0, slash));
		String rest = slash < 0 ? "" : remainder.substring(slash + 1);
		int next = rest.indexOf('/');
		String action = phpSegment(next < 0 ? rest : rest.substring(0, next));

		int status;
		String key;
		Map<String, Object> replace;
		if (module.isEmpty() || !LegacyPhpModules.isAllowed(module)) {
			status = 404;
			key = "module_not_found";
			// PHP's `$module ?: 'none'` -- an empty segment is reported as the
			// literal string, not as an absent value.
			replace = Map.of("module", module.isEmpty() ? "none" : module,
					"list", LegacyPhpModules.ALLOWED_CSV);
		} else if (action.isEmpty()) {
			status = 404;
			key = "unknown_action";
			replace = Map.of();
		} else {
			status = 501;
			key = "module_not_implemented";
			replace = Map.of("module", module + "/" + action);
		}

		String text = messages.translate(messages.resolveLocale(request), key, replace);
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write(objectMapper.writeValueAsString(LegacyApiResponse.fail(text, null)));
	}

}
