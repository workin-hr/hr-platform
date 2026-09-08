package com.workin.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.filter.AssignableTypeFilter;

/**
 * {@link Phase1SchemaCheck} runs before every other {@link ApplicationRunner}.
 *
 * <p>Measured on an unprovisioned database under the {@code prod} profile:
 * {@code PlatformAdminBootstrap} ran first, its {@code count(*) from
 * platform_admins} threw {@code Table 'workin.platform_admins' doesn't exist},
 * the context died, and the schema check -- written to explain exactly that
 * situation, naming the file to apply and the runbook to follow -- never ran.
 * The operator got a Hibernate stack trace.
 *
 * <p>Bean-definition order is not a guarantee, and the next runner somebody
 * adds could land anywhere in it. So the ordering is declared with
 * {@code @Order} and asserted here, over every runner on the classpath rather
 * than the two that exist today.
 */
class StartupRunnerOrderTest {

	@Test
	@DisplayName("the Phase 1 schema check runs before any other startup runner")
	void theSchemaCheckIsFirst() {
		List<Class<?>> runners = applicationRunners();

		assertThat(runners)
				.describedAs("no ApplicationRunner found -- this guard would pass vacuously")
				.hasSizeGreaterThan(1);
		assertThat(runners).contains(Phase1SchemaCheck.class);

		List<Class<?>> ordered = new ArrayList<>(runners);
		ordered.sort(AnnotationAwareOrderComparator.INSTANCE);

		assertThat(ordered.getFirst())
				.describedAs("a runner that throws before the schema check silences the one "
						+ "diagnostic an operator needs when the Phase 1 tables are missing. "
						+ "Give the new runner a lower precedence, not this one")
				.isEqualTo(Phase1SchemaCheck.class);
	}

	/**
	 * Every runner in either profile, not the ones one profile happens to
	 * activate.
	 *
	 * <p>{@code @Profile} is a {@code @Conditional}, and the scanner evaluates
	 * conditions against its environment -- so a single scan silently omits
	 * half the runners, {@link Phase1SchemaCheck} among them. That is how the
	 * first version of this test passed its ordering assertion while never
	 * looking at the class it exists to protect. Two scans, unioned.
	 */
	private static List<Class<?>> applicationRunners() {
		List<Class<?>> found = new ArrayList<>();
		for (String[] profiles : new String[][] { { "phase1-mysql" }, {} }) {
			StandardEnvironment environment = new StandardEnvironment();
			environment.setActiveProfiles(profiles);
			ClassPathScanningCandidateComponentProvider scanner =
					new ClassPathScanningCandidateComponentProvider(false, environment);
			scanner.addIncludeFilter(new AssignableTypeFilter(ApplicationRunner.class));
			for (BeanDefinition definition : scanner.findCandidateComponents("com.workin")) {
				try {
					Class<?> runner = Class.forName(definition.getBeanClassName());
					if (!found.contains(runner)) {
						found.add(runner);
					}
				}
				catch (ClassNotFoundException ex) {
					throw new IllegalStateException(definition.getBeanClassName(), ex);
				}
			}
		}
		return found;
	}

}
