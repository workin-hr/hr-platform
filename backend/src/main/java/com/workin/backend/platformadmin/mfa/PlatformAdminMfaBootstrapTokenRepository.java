package com.workin.backend.platformadmin.mfa;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformAdminMfaBootstrapTokenRepository
		extends JpaRepository<PlatformAdminMfaBootstrapToken, Long> {

	Optional<PlatformAdminMfaBootstrapToken> findByTokenHash(String tokenHash);

	List<PlatformAdminMfaBootstrapToken> findByPlatformAdminIdAndUsedAtIsNullAndRevokedAtIsNull(
			Long platformAdminId);

}
