package com.workin.legacy.auth;

/** Mirrors {@code com.workin.backend.identity.RefreshTokenStatus} exactly -- same state machine, different identity domain. */
public enum LegacyRefreshTokenStatus {
	ACTIVE,
	ROTATED,
	REVOKED
}
