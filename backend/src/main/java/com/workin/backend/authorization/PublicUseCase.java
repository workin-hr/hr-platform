package com.workin.backend.authorization;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that this externally reachable use case is intentionally
 * public -- reachable without any authentication (ADR-0010 Dimension
 * 4's explicit public marker; F-23). The mandatory reason makes the
 * intent reviewable at the declaration site, and
 * AuthorizationPolicyArchTest fails the build on any handler that
 * declares no policy at all.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicUseCase {

	String reason();

}
