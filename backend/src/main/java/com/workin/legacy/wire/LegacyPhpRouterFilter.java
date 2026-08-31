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
		// strtolower(preg_replace('/[^a-z0-9_]/', '', $segment)) -- the strip
		// runs on the RAW segment and the lowercase after it, which is not the
		// same as lowercasing first. `[^a-z0-9_]` does not include A-Z, so an
		// uppercase letter is DELETED rather than folded: PHP reads `Configs`
		// as `onfigs` and `CONFIGS` as the empty string. Measured against the
		// running PHP, which answers "Module 'onfigs' not found" and
		// "Module 'none' not found" respectively. An earlier version of this
		// method lowercased first and so read both as `configs`, quietly
		// serving two paths legacy refuses.
		StringBuilder out = new StringBuilder(raw.length());
		for (char c : raw.toCharArray()) {
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
				out.append(c);
			}
		}
		return out.toString();
	}

	/** The two segments after {@code api}, normalized as {@code index.php} does. */
	private static String[] moduleAndAction(String uri) {
		String remainder = uri.substring(API_PREFIX.length());
		int slash = remainder.indexOf('/');
		String module = phpSegment(slash < 0 ? remainder : remainder.substring(0, slash));
		String rest = slash < 0 ? "" : remainder.substring(slash + 1);
		int next = rest.indexOf('/');
		String action = phpSegment(next < 0 ? rest : rest.substring(0, next));
		return new String[] { module, action };
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String uri = request.getRequestURI();
		if (!uri.startsWith(API_PREFIX)) {
			filterChain.doFilter(request, response);
			return;
		}

		Set<String> served = mappedRoutes.get();
		// Fail OPEN, not closed. Every refusal below is decided by absence from
		// this set, so an empty one would answer 501 for the entire client
		// surface -- the single worst outcome available to this filter, and
		// exactly the D-111 break it exists to prevent. An empty set cannot
		// mean "nothing is served"; it means the mapping was not readable, so
		// the request is passed through to the dispatcher, which is the
		// behaviour that existed before this filter.
		if (served.isEmpty()) {
			filterChain.doFilter(request, response);
			return;
		}

		// The literal `.php` form first, unnormalized. Those are the paths the
		// controllers are actually mapped on, and normalization would eat the
		// dot -- `list.php` becomes `listphp` -- so testing the normalized form
		// first turns every delivered `.php` route into a 501.
		if (served.contains(uri)) {
			filterChain.doFilter(request, response);
			return;
		}

		// Then the client's form, resolved against NORMALIZED segments rather
		// than the raw path. PHP strips `[^a-z0-9_]` before it looks for the
		// action file, so `/apis/api/con-figs/get` and `/apis/api/con.figs/get`
		// both serve `configs/get.php` -- both measured at 200 against the
		// running PHP. Matching on the raw path refused them as 501, turning
		// routes legacy serves into errors.
		String[] parts = moduleAndAction(uri);
		String module = parts[0];
		String action = parts[1];
		String rewritten = module.isEmpty() || action.isEmpty()
				? null : API_PREFIX + module + "/" + action + PHP_SUFFIX;

		if (rewritten == null || !served.contains(rewritten)) {
			writeRouterRefusal(request, response, module, action);
			return;
		}
		String rewrittenPath = rewritten;
		filterChain.doFilter(new HttpServletRequestWrapper(request) {
			@Override
			public String getRequestURI() {
				return rewrittenPath;
			}

			@Override
			public StringBuffer getRequestURL() {
				String original = ((HttpServletRequest) getRequest()).getRequestURL().toString();
				// Rebuild from the rewritten path rather than appending: extra
				// segments are dropped, so the suffix is not simply added.
				int pathStart = original.indexOf(uri);
				return new StringBuffer(
						pathStart < 0 ? original : original.substring(0, pathStart) + rewrittenPath);
			}

			@Override
			public String getServletPath() {
				return rewrittenPath;
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
	private void writeRouterRefusal(
			HttpServletRequest request, HttpServletResponse response, String module, String action)
			throws IOException {
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

		String text = messages.translate(safeLocale(request), key, replace);
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write(objectMapper.writeValueAsString(LegacyApiResponse.fail(text, null)));
	}

	/**
	 * {@link LegacyMessages#resolveLocale} reads {@code ?lang=} through
	 * {@code URLDecoder}, which throws on a malformed escape such as
	 * {@code ?lang=%}. PHP's {@code parse_str} keeps the literal {@code %} and
	 * the router answers its normal 404, so letting that exception escape
	 * turned a refusal into a <b>500</b> -- an invalid <em>optional</em>
	 * localization hint deciding the status of the whole response.
	 *
	 * <p><b>The malformed parameter is not necessarily the one being read.</b>
	 * {@code ?lang=ar&x=%} is a valid Arabic request with unrelated garbage
	 * beside it: PHP keeps both and answers in Arabic. Discarding the whole
	 * query on any decode failure answered that in English -- a second, quieter
	 * divergence introduced by the fix for the first. So the retry preserves
	 * the {@code lang} pair verbatim and drops only the rest, and gives up
	 * entirely just when {@code lang} is itself the malformed one.
	 */
	private String safeLocale(HttpServletRequest request) {
		try {
			return messages.resolveLocale(request);
		} catch (IllegalArgumentException ex) {
			// Narrow deliberately: URLDecoder's malformed-escape failure is an
			// IllegalArgumentException, and that is the only failure a caller
			// can provoke here. Catching RuntimeException would also swallow a
			// genuine bug inside resolveLocale and answer 404 as though the
			// route were simply unknown.
			String langOnly = onlyLangPair(request.getQueryString());
			return messages.resolveLocale(new HttpServletRequestWrapper(request) {
				@Override
				public String getQueryString() {
					return langOnly;
				}
			});
		}
	}

	/**
	 * The {@code lang} pair alone, with any dangling {@code %} escaped so it
	 * survives decoding, or {@code null} if there is none. The <em>last</em>
	 * occurrence wins, matching {@code parse_str}, which is also what
	 * {@link LegacyQueryParameters} does for duplicates.
	 *
	 * <p><b>Escaped, not dropped, and the difference is client-visible.</b>
	 * {@code parse_str} keeps a malformed {@code lang=%} as the literal string
	 * {@code "%"} — a nonempty value that is not {@code ar}, so it selects
	 * English and <em>overrides</em> an {@code Accept-Language: ar} header,
	 * exactly as {@code ?lang=xx} would. Measured against the running PHP.
	 * Removing the pair instead made the request look like it had no
	 * {@code lang} at all, so the header won and the refusal came back in
	 * Arabic where legacy answers English.
	 */
	private static String onlyLangPair(String query) {
		if (query == null) {
			return null;
		}
		String found = null;
		for (String pair : query.split("&")) {
			if (pair.equals("lang") || pair.startsWith("lang=")) {
				found = pair;
			}
		}
		return found == null ? null : escapeDanglingPercents(found);
	}

	/**
	 * Percent-escapes any {@code %} that does not begin a valid escape, so
	 * {@code URLDecoder} yields the literal characters {@code parse_str} would
	 * have kept rather than throwing.
	 */
	private static String escapeDanglingPercents(String pair) {
		StringBuilder out = new StringBuilder(pair.length() + 8);
		for (int i = 0; i < pair.length(); i++) {
			char c = pair.charAt(i);
			if (c == '%' && !(i + 2 < pair.length()
					&& isHex(pair.charAt(i + 1)) && isHex(pair.charAt(i + 2)))) {
				out.append("%25");
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	private static boolean isHex(char c) {
		return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
	}

}
