package com.workin.backend.openapi;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The published document is the contract a client developer reads before
 * writing a line, and springdoc's raw output states two things that are false:
 * that every route accepts every verb, and that the URL carries a {@code .php}
 * suffix. {@code legacyClientContract()} is what makes it true, so these cases
 * check it against the real committed inventory rather than a fixture -- a
 * customizer that silently stops matching would leave a document that still
 * renders, still lists 202 routes, and lies about all of them.
 */
class OpenApiConfigTest {

	private final OpenApiCustomizer customizer = new OpenApiConfig().legacyClientContract();

	private static OpenAPI documentOf(String... paths) {
		Paths all = new Paths();
		for (String path : paths) {
			PathItem item = new PathItem();
			for (PathItem.HttpMethod verb : PathItem.HttpMethod.values()) {
				item.operation(verb, new Operation().operationId(verb + " " + path));
			}
			all.addPathItem(path, item);
		}
		return new OpenAPI().paths(all);
	}

	private static Set<String> verbsAt(OpenAPI document, String path) {
		PathItem item = document.getPaths().get(path);
		assertThat(item).as("path %s is present", path).isNotNull();
		return item.readOperationsMap().keySet().stream()
				.map(Enum::name)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	@Test
	@DisplayName("a route with one guard keeps only the verb its handler accepts")
	void prunesToTheGuardedVerb() {
		OpenAPI document = documentOf("/apis/api/auth/login_company.php");

		customizer.customise(document);

		assertThat(verbsAt(document, "/apis/api/auth/login_company")).containsExactly("POST");
	}

	@Test
	@DisplayName("a route its handler branches on keeps every branch")
	void keepsEveryBranchOfATwoVerbHandler() {
		OpenAPI document = documentOf("/apis/api/profile/employee.php");

		customizer.customise(document);

		assertThat(verbsAt(document, "/apis/api/profile/employee"))
				.containsExactlyInAnyOrder("GET", "PUT");
	}

	@Test
	@DisplayName("a route with no guard keeps every verb, because PHP has no guard either")
	void leavesAnUnguardedRouteAlone() {
		OpenAPI document = documentOf("/apis/api/employees/template_excel.php");

		customizer.customise(document);

		assertThat(verbsAt(document, "/apis/api/employees/template_excel"))
				.hasSize(PathItem.HttpMethod.values().length);
	}

	@Test
	@DisplayName("the .php suffix is dropped, because that form answers 500 in production")
	void publishesTheUrlClientsCall() {
		OpenAPI document = documentOf("/apis/api/configs/get.php");

		customizer.customise(document);

		assertThat(document.getPaths()).containsOnlyKeys("/apis/api/configs/get");
	}

	@Test
	@DisplayName("a path that is not a legacy file is left exactly as it is")
	void leavesNonLegacyPathsUntouched() {
		OpenAPI document = documentOf("/actuator/health");

		customizer.customise(document);

		assertThat(document.getPaths()).containsOnlyKeys("/actuator/health");
		assertThat(verbsAt(document, "/actuator/health"))
				.hasSize(PathItem.HttpMethod.values().length);
	}

	@Test
	@DisplayName("running twice changes nothing, which is what makes the double registration safe")
	void isIdempotent() {
		// springdoc builds each group's resource from that group's own
		// customizer set, so this bean is registered on the group AND globally
		// for the ungrouped document. On a grouped request it therefore runs
		// more than once over the same OpenAPI instance.
		OpenAPI document = documentOf("/apis/api/auth/login_company.php", "/apis/api/profile/employee.php");

		customizer.customise(document);
		Map<String, Set<String>> afterOnce = Map.of(
				"/apis/api/auth/login_company", verbsAt(document, "/apis/api/auth/login_company"),
				"/apis/api/profile/employee", verbsAt(document, "/apis/api/profile/employee"));

		customizer.customise(document);

		assertThat(document.getPaths().keySet())
				.containsExactlyInAnyOrderElementsOf(afterOnce.keySet());
		afterOnce.forEach((path, verbs) -> assertThat(verbsAt(document, path)).isEqualTo(verbs));
	}

	@Test
	@DisplayName("the inventory the pruning reads is on the classpath and covers the whole surface")
	void theInventoryIsPackagedWithTheApplication() {
		// A missing resource fails soft at runtime -- deliberately, so a
		// description problem cannot stop the service booting. This is what
		// notices that it went missing.
		List<String> routes = new java.io.BufferedReader(new java.io.InputStreamReader(
				java.util.Objects.requireNonNull(
						OpenApiConfig.class.getClassLoader()
								.getResourceAsStream("legacy/route-methods.txt"),
						"legacy/route-methods.txt is not on the classpath"),
				java.nio.charset.StandardCharsets.UTF_8))
				.lines()
				.filter(line -> line.startsWith("/apis"))
				.toList();

		assertThat(routes).hasSize(202);
	}

}
