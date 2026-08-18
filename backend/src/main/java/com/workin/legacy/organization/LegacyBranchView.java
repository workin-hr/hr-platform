package com.workin.legacy.organization;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The legacy {@code public_row($branch)} shape used by {@code one},
 * {@code create}, {@code update} and {@code generate_qr} -- every
 * column, no {@code employees_count} (that field only exists on {@code
 * list.php}'s per-row shape, see {@link LegacyBranchListItem}).
 */
public record LegacyBranchView(
		Long id, Long companyId, String name, String address, BigDecimal latitude, BigDecimal longitude,
		Integer radiusMeters, String qrCode, Instant expiresAt, boolean isActive, Instant createdAt) {

	static LegacyBranchView of(LegacyBranch branch) {
		return new LegacyBranchView(
				branch.getId(), branch.getCompanyId(), branch.getName(), branch.getAddress(),
				branch.getLatitude(), branch.getLongitude(), branch.getRadiusMeters(), branch.getQrCode(),
				branch.getExpiresAt(), branch.active(), branch.getCreatedAt());
	}

}
