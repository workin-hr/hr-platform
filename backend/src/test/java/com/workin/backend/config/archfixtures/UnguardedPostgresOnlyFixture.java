package com.workin.backend.config.archfixtures;

import org.springframework.stereotype.Service;

/**
 * The violation {@link com.workin.backend.config.ProfileCoverageArchTest}
 * exists to catch: a Spring-managed bean with no
 * {@code @Profile("!phase1-mysql")} guard. Not scanned into the real
 * mixed packages -- imported explicitly by the test that proves the
 * rule fails on its own shape.
 */
@Service
public class UnguardedPostgresOnlyFixture {
}
