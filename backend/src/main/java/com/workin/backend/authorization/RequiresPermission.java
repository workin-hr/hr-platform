package com.workin.backend.authorization;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the catalog permission this use case requires (ADR-0010
 * Dimensions 3/4; F-23). The value must be a {@link PermissionKeys}
 * constant, never a string literal -- the permissions table (V4) is
 * the single source of truth and PermissionCatalogSyncTest keeps
 * {@link PermissionKeys} in exact sync with it.
 *
 * <p><strong>Frozen</strong>: AuthorizationPolicyArchTest fails the
 * build on any usage of this annotation until the runtime
 * permission-evaluation component exists (F-15/F-17) -- an annotation
 * without enforcement would be decorative security. The PR that lands
 * enforcement removes that freeze rule and wires this annotation to
 * the real check.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

	String value();

}
