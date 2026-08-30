package com.workin.legacy.wire;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

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

	/**
	 * Legacy's router matches {@code /api/{module}/{action}} and nothing
	 * deeper: {@code index.php} reads exactly two path segments after
	 * {@code api} and ignores the rest. A path with more segments is not a
	 * route legacy would serve, so it is left alone rather than rewritten into
	 * a file name that does not exist.
	 */
	private static boolean isRoutableLegacyPath(String path) {
		if (!path.startsWith(API_PREFIX) || path.endsWith(PHP_SUFFIX)) {
			return false;
		}
		String remainder = path.substring(API_PREFIX.length());
		int slash = remainder.indexOf('/');
		if (slash <= 0 || slash == remainder.length() - 1) {
			return false;
		}
		return remainder.indexOf('/', slash + 1) < 0;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String uri = request.getRequestURI();
		if (!isRoutableLegacyPath(uri)) {
			filterChain.doFilter(request, response);
			return;
		}
		String rewritten = uri + PHP_SUFFIX;
		filterChain.doFilter(new HttpServletRequestWrapper(request) {
			@Override
			public String getRequestURI() {
				return rewritten;
			}

			@Override
			public StringBuffer getRequestURL() {
				StringBuffer original = ((HttpServletRequest) getRequest()).getRequestURL();
				return new StringBuffer(original.toString() + PHP_SUFFIX);
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

}
