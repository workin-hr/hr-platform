package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;


/**
 * Which dashboard pages this deployment serves, read from the handler mapping.
 *
 * <p>The sibling of {@code LegacyPhpRouteInventoryTest} for the other surface,
 * and it exists because the obvious alternative has now been wrong twice.
 * Grepping the sources for route strings misses a class-level
 * {@code @RequestMapping} prefix composed with a method-level suffix, and
 * misses an array-valued mapping such as
 * {@code @RequestMapping({"/list.php", "/summary.php"})} -- which between them
 * hid most of the API surface and produced two different wrong completion
 * figures. Spring composes those; a regular expression does not. So this asks
 * Spring.
 *
 * <p>The legacy side is equally not a directory listing: three directories
 * under {@code dashboard/pages/} carry no {@code page.php}, {@code index.php}
 * is the home page rather than a {@code pages/} entry, and two routes reach a
 * {@code detail.php} only through a rewrite. That set lives in
 * {@code contracts/legacy-dashboard-pages.txt}, generated and drift-checked by
 * {@code scripts/check_dashboard_page_drift.py}.
 *
 * <p>Pinned to {@code phase1-mysql}. Most of these pages are backed by tables
 * that exist only in the legacy schema, so their controllers carry that
 * profile and the same build serves a different set without it -- under the
 * default profile this surface is three pages, which is a true answer to a
 * different question.
 */
@SpringBootTest(classes = BackendApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("phase1-mysql")
class AdminDashboardPageInventoryTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	static {
		MARIADB.start();
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
		registry.add("app.platform-admin.mfa.encryption-key", () -> {
			byte[] key = new byte[32];
			new java.security.SecureRandom().nextBytes(key);
			return java.util.Base64.getEncoder().encodeToString(key);
		});
	}

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	/**
	 * Pages this surface serves that the PHP dashboard has no equivalent for.
	 *
	 * <p>Not gaps in either direction: the second factor and its enrolment are
	 * D-152's, and individual session listing and revocation is ADR-0015
	 * prerequisite 13. Legacy authenticates with a single shared password and
	 * has none of them.
	 */
	private static final Set<String> JAVA_ONLY_PAGES = Set.of(
			"mfa", "enrol", "logout", "sessions", "_assets");

	/**
	 * Legacy pages this surface serves at a different URL.
	 *
	 * <p>Legacy reaches one company's detail through a rewrite of
	 * {@code company_detail.php}; here it is {@code /admin/companies/{companyId}},
	 * a path variable under the list it comes from. The page is ported -- the
	 * shape is not the same.
	 *
	 * <p>Worth naming because the sibling did the opposite: employee detail kept
	 * legacy's {@code /admin/employee_detail}, so the two details of the same
	 * surface disagree with each other. Neither is wrong, but the divergence
	 * should be visible in an inventory rather than absorbed by it.
	 */
	private static final Set<String> SERVED_UNDER_ANOTHER_PATH = Set.of("company_detail");

	/**
	 * Legacy pages with no controller here yet.
	 *
	 * <p>Listed rather than counted so that porting one is a visible deletion
	 * from this file, and so that a page cannot quietly stop being served
	 * without the test noticing.
	 *
	 * <p>Empty as of D-191: every page ADR-0016 still covers is served.
	 */
	private static final Set<String> NOT_YET_PORTED = Set.of();

	/**
	 * Legacy pages this surface will never serve, by decision rather than by
	 * backlog.
	 *
	 * <p>**D-192** narrowed ADR-0016 to the platform-admin audience. These three
	 * are the only pages legacy gates *away from* a platform administrator --
	 * {@code profile} and {@code change_password} open with
	 * {@code if (isAdmin()) redirect}, {@code company_settings} with
	 * {@code if (!isCompany() && !isHr()) redirect} -- so serving them here would
	 * mean either relaxing a guard legacy applies or building the company-owner
	 * and HR web logins, which that decision withdrew. Every capability on them
	 * has a ported API route and a screen in the desktop client, so nothing is
	 * lost at cutover.
	 *
	 * <p>Kept as its own list rather than folded into {@link #NOT_YET_PORTED}
	 * so that "nobody has got to it yet" and "we decided not to" stay different
	 * statements. A page appearing here later should be a decision someone
	 * records, not a backlog item that quietly stopped moving.
	 */
	private static final Set<String> OUT_OF_SCOPE = Set.of(
			"change_password", "company_settings", "profile");

	@Test
	void everyServedPageIsEitherALegacyPageOrDeclaredJavaOnly() {
		Set<String> unexpected = new TreeSet<>(servedPages());
		unexpected.removeAll(legacyPages());
		unexpected.removeAll(JAVA_ONLY_PAGES);
		assertThat(unexpected)
				.as("a page served here that legacy does not have and that is not "
						+ "declared Java-only is either a typo in a mapping or an "
						+ "invented surface")
				.isEmpty();
	}

	@Test
	void theUnportedSetIsExactlyWhatIsDeclared() {
		Set<String> remaining = new TreeSet<>(legacyPages());
		remaining.removeAll(servedPages());
		remaining.removeAll(SERVED_UNDER_ANOTHER_PATH);
		Set<String> declared = new TreeSet<>(NOT_YET_PORTED);
		declared.addAll(OUT_OF_SCOPE);
		assertThat(remaining)
				.as("porting a page should delete it from NOT_YET_PORTED; losing one "
						+ "should fail here rather than pass quietly. A page that is neither "
						+ "served nor declared in one of the two lists is unaccounted for.")
				.containsExactlyElementsOf(declared);
	}

	@Test
	void theTwoUnservedListsSayDifferentThings() {
		// A page is either work not done or work decided against, never both --
		// and OUT_OF_SCOPE must stay a decision someone recorded rather than a
		// place backlog goes to be forgotten.
		assertThat(NOT_YET_PORTED)
				.as("a page cannot be both unstarted and out of scope")
				.doesNotContainAnyElementsOf(OUT_OF_SCOPE);
		assertThat(OUT_OF_SCOPE)
				.as("D-192's three, and nothing that has quietly joined them")
				.containsExactlyInAnyOrder("change_password", "company_settings", "profile");
	}

	@Test
	void theManifestAndTheDeclarationsPartitionTheSurface() {
		Set<String> legacy = legacyPages();
		Set<String> served = servedPages();
		Set<String> portedLegacy = new TreeSet<>(legacy);
		portedLegacy.retainAll(served);
		portedLegacy.addAll(SERVED_UNDER_ANOTHER_PATH);

		// 35: guide_videos became a real surface when hr-legacy committed it in
		// 505004f, which is what R-063 was waiting for.
		assertThat(legacy).as("the committed manifest").hasSize(35);
		assertThat(portedLegacy)
				.hasSize(legacy.size() - NOT_YET_PORTED.size() - OUT_OF_SCOPE.size());
		assertThat(NOT_YET_PORTED).doesNotHaveDuplicates();
		// Neither list may overlap the served set: a page cannot be both ported
		// and not, nor both ported and decided against.
		assertThat(NOT_YET_PORTED).doesNotContainAnyElementsOf(portedLegacy);
		assertThat(OUT_OF_SCOPE).doesNotContainAnyElementsOf(portedLegacy);
	}

	/** Distinct first path segments under {@code /admin}, as Spring resolves them. */
	private Set<String> servedPages() {
		Set<String> pages = new TreeSet<>();
		for (RequestMappingInfo info : this.handlerMapping.getHandlerMethods().keySet()) {
			if (info.getPathPatternsCondition() == null) {
				continue;
			}
			for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
				if (!pattern.startsWith("/admin")) {
					continue;
				}
				String rest = pattern.substring("/admin".length());
				if (rest.isEmpty() || rest.equals("/")) {
					pages.add("index");
					continue;
				}
				String first = rest.substring(1).split("/")[0];
				// A variable segment is not a page of its own.
				if (!first.startsWith("{") && !first.isEmpty()) {
					pages.add(first);
				}
			}
		}
		return pages;
	}

	private static Set<String> legacyPages() {
		Path manifest = Path.of("..", "contracts", "legacy-dashboard-pages.txt");
		try {
			List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
			Set<String> pages = new TreeSet<>();
			for (String line : lines) {
				String trimmed = line.trim();
				if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
					pages.add(trimmed);
				}
			}
			return pages;
		}
		catch (Exception ex) {
			throw new IllegalStateException("could not read " + manifest.toAbsolutePath(), ex);
		}
	}

}
