package com.workin.backend.openapi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The published API description.
 *
 * <p><b>Why this class exists rather than just the dependency.</b> Every legacy
 * route is mapped with a bare {@code @RequestMapping(path)} and no
 * {@code method =}, deliberately: the handler checks the method itself and
 * answers PHP's own {@code 405 invalid_method} in the legacy envelope. Letting
 * Spring answer 405 first, in its own shape, would be a client-visible change
 * and D-111 forbids that. The paths are the PHP <em>files</em> too, because the
 * inventory was built from the PHP source tree -- and those are not the URLs
 * clients call.
 *
 * <p>Left alone, springdoc would therefore publish a document that states
 * {@code GET /apis/api/auth/login_company.php} is valid. Both halves of that
 * are wrong: the route answers 405 to GET, and the {@code .php} form answers
 * 500 against the system this replaces. A specification that lies about the
 * contract is worse than no specification, because a client developer has no
 * reason to doubt it. {@link #legacyClientContract()} corrects both.
 *
 * <p><b>What this document does not tell you.</b> {@code data} and {@code meta}
 * on the envelope are typed {@code Object}, because the port returns PHP's own
 * maps rather than re-typing 202 endpoints -- that is what makes it a faithful
 * port. The document therefore describes the routes, their verbs and the
 * envelope, and says nothing about the shape inside {@code data}. That is
 * stated in the description rather than left for someone to discover.
 */
@Configuration
public class OpenApiConfig {

	private static final Logger log = LoggerFactory.getLogger(OpenApiConfig.class);

	private static final String ROUTE_METHODS = "legacy/route-methods.txt";

	private static final String PHP_SUFFIX = ".php";

	/** A route with no method guard, which is faithful: PHP has none either. */
	private static final String ANY = "ANY";

	private static final String DESCRIPTION = """
			The Workin API, as the Flutter mobile and desktop clients call it.

			**This is a port, not a redesign.** Every route reproduces the PHP \
			endpoint of the same path, byte for byte where it matters, so the \
			clients need no change at cutover.

			**Responses share one envelope**: `{success, message, data?, meta?}`. \
			`data` and `meta` are typed as free-form objects here because the \
			port returns PHP's own structures rather than re-typing 202 \
			endpoints -- so this document is authoritative about *which routes \
			exist and which verb each accepts*, and silent about the shape \
			inside `data`. For that, call the endpoint, or read the Flutter \
			client, which already encodes it.

			**Verbs are exact.** Each route is mapped without a method \
			restriction so the handler can answer PHP's own \
			`405 invalid_method`; the verbs shown here are pruned to what each \
			handler actually accepts.

			**Paths are the URL clients call**, without the `.php` suffix. The \
			suffix is the file that serves the route, not the route: against \
			the PHP system this replaces, `configs/get` answers 200 and \
			`configs/get.php` answers 500.

			**Authentication** is a bearer token from `auth/login_company`, \
			`auth/login_employee` or `auth/login_desktop`. The token is at \
			`data.token`.
			""";

	@Bean
	public OpenAPI workinOpenApi() {
		return new OpenAPI()
				.info(new Info()
						.title("Workin API")
						.version("Phase 1")
						.description(DESCRIPTION)
						.license(new License().name("Proprietary")))
				.schemaRequirement("bearerAuth", new SecurityScheme()
						.type(SecurityScheme.Type.HTTP)
						.scheme("bearer")
						.bearerFormat("JWT")
						.description("The `data.token` returned by any of the three login routes."));
	}

	/**
	 * The client surface: the 202 legacy routes, and nothing else.
	 *
	 * <p>The platform-admin API is a separate group rather than mixed in. It is
	 * a different audience with different credentials, and a client developer
	 * scrolling past administrative endpoints looking for theirs is a document
	 * that has stopped helping.
	 *
	 * <p>The customizer is attached to the group as well as being a bean:
	 * springdoc builds each group's resource from that group's own customizer
	 * set (see {@code MultipleOpenApiResource}), so a bean alone reaches the
	 * ungrouped {@code /v3/api-docs} and no group. It is written to be
	 * idempotent because of exactly that double registration.
	 */
	@Bean
	public GroupedOpenApi clientApi(OpenApiCustomizer legacyClientContract) {
		return GroupedOpenApi.builder()
				.group("client-api")
				.displayName("Client API (mobile and desktop)")
				.pathsToMatch("/apis/**")
				.addOpenApiCustomizer(legacyClientContract)
				.build();
	}

	@Bean
	public GroupedOpenApi platformAdminApi() {
		return GroupedOpenApi.builder()
				.group("platform-admin")
				.displayName("Platform administration")
				.pathsToMatch("/api/platform-admin/**")
				.build();
	}

	/**
	 * Publishes what each legacy route accepts, at the URL clients call.
	 *
	 * <p>One pass does both corrections, because they are entangled: the method
	 * inventory is keyed on the {@code .php} file path, so a verb can only be
	 * looked up while the key still carries the suffix, and the path can only be
	 * rewritten once that lookup has happened.
	 *
	 * <p><b>The suffix.</b> {@code LegacyPhpRouterFilter} records the
	 * measurement against production on 2026-08-31: {@code GET
	 * /apis/api/configs/get} answers <b>200</b> and {@code .../get.php} answers
	 * <b>500</b>, because {@code .htaccess} rewrites only when the target does
	 * not exist on disk and a directly-requested PHP file runs without its
	 * helpers. Both Flutter clients agree -- not one of
	 * {@code api_constants.dart}'s endpoint constants ends in {@code .php} -- so
	 * a document showing the file form would hand a client developer a URL that
	 * works against this port, which serves both, and fails against the system
	 * it replaces.
	 *
	 * <p><b>The verbs</b> come from {@code legacy/route-methods.txt}, which
	 * {@code scripts/check_openapi_route_methods_drift.py} generates from the
	 * handlers' own guards and keeps current as a build gate.
	 *
	 * <p>Fails soft: if the inventory cannot be read, the document is published
	 * unpruned with a warning rather than the application refusing to start. A
	 * missing description is a smaller problem than a service that will not
	 * boot, and the build gate is what stops the file going missing in the first
	 * place.
	 */
	@Bean
	public OpenApiCustomizer legacyClientContract() {
		Map<String, Set<String>> allowed = loadRouteMethods();
		return openApi -> {
			if (openApi.getPaths() == null) {
				return;
			}
			Paths rewritten = new Paths();
			rewritten.setExtensions(openApi.getPaths().getExtensions());
			openApi.getPaths().forEach((path, item) -> {
				pruneVerbs(allowed.get(path), item);
				rewritten.addPathItem(
						path.endsWith(PHP_SUFFIX)
								? path.substring(0, path.length() - PHP_SUFFIX.length())
								: path,
						item);
			});
			openApi.setPaths(rewritten);
		};
	}

	private static void pruneVerbs(Set<String> verbs, PathItem item) {
		if (verbs == null || verbs.contains(ANY)) {
			return;
		}
		for (PathItem.HttpMethod verb : PathItem.HttpMethod.values()) {
			if (!verbs.contains(verb.name())) {
				item.operation(verb, null);
			}
		}
	}

	private static Map<String, Set<String>> loadRouteMethods() {
		Map<String, Set<String>> routes = new HashMap<>();
		try (InputStream stream = OpenApiConfig.class.getClassLoader()
				.getResourceAsStream(ROUTE_METHODS)) {
			if (stream == null) {
				log.warn("{} is not on the classpath; the OpenAPI document will show every HTTP "
						+ "method for every legacy route, which overstates what they accept",
						ROUTE_METHODS);
				return routes;
			}
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(stream, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					String trimmed = line.trim();
					if (trimmed.isEmpty() || trimmed.startsWith("#")) {
						continue;
					}
					int space = trimmed.lastIndexOf(' ');
					if (space < 0) {
						continue;
					}
					Set<String> verbs = new LinkedHashSet<>();
					for (String verb : trimmed.substring(space + 1).split(",")) {
						verbs.add(verb.trim());
					}
					routes.put(trimmed.substring(0, space), verbs);
				}
			}
		}
		catch (IOException ex) {
			log.warn("could not read {}; the OpenAPI document will not be pruned", ROUTE_METHODS, ex);
			return Map.of();
		}
		return routes;
	}

}
