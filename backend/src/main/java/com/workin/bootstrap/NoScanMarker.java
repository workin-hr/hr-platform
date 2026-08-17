package com.workin.bootstrap;

/**
 * Exists only to anchor {@code @SpringBootApplication}'s
 * {@code scanBasePackageClasses} at an empty package (ADR-0013 / D-043).
 *
 * <p>{@code scanBasePackages = {}} does not do what it looks like it
 * does: with both {@code basePackages} and {@code basePackageClasses}
 * empty, Spring's {@code ComponentScanAnnotationParser} falls back to
 * the annotated class's own package -- so an empty array silently
 * re-scans {@code com.workin.backend} in full, exactly the implicit
 * behaviour this profile split exists to replace. Pointing at this
 * marker's package instead gives {@code @ComponentScan} a real,
 * genuinely empty base, so nothing is found by default and
 * {@code PostgresPersistenceConfig}/{@code LegacyPersistenceConfig}'s
 * own {@code @ComponentScan}s are the only source of beans from
 * {@code com.workin.backend}/{@code com.workin.legacy}.
 */
public final class NoScanMarker {

	private NoScanMarker() {
	}

}
