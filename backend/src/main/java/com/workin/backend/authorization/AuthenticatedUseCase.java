package com.workin.backend.authorization;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that this externally reachable use case requires an
 * authenticated principal but no specific catalog permission (ADR-0010
 * Dimension 4's authentication-only marker; F-23) -- context/identity
 * lookups like "who am I" endpoints. Business capabilities always use
 * {@link RequiresPermission} instead; the mandatory reason makes the
 * no-permission claim reviewable at the declaration site.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthenticatedUseCase {

	String reason();

}
