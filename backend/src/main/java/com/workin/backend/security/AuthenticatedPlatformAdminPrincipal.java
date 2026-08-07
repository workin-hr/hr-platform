package com.workin.backend.security;

/**
 * The identity extracted from a validated platform-admin JWT
 * ({@code com.workin.backend.platformadmin.PlatformAdminJwtService}).
 * Deliberately a distinct type from {@link AuthenticatedPrincipal} --
 * the two token families and principal types are never
 * interchangeable, matching the structural platform/tenant domain
 * separation in docs/architecture/authorization-model.md §8.
 */
public record AuthenticatedPlatformAdminPrincipal(Long platformAdminId) {
}
