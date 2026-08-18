package com.workin.legacy.organization;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * {@code list.php}'s per-row shape: every {@link LegacyBranchView} field
 * plus {@code employees_count} -- a correlated subquery in PHP
 * (accepted-roster, active employees assigned to the branch), computed
 * per row here via {@code LegacyEmployeeRepository#countActiveRosterByBranchIdAndCompanyId}.
 */
public record LegacyBranchListItem(
		Long id, Long companyId, String name, String address, BigDecimal latitude, BigDecimal longitude,
		Integer radiusMeters, String qrCode, Instant expiresAt, boolean isActive, Instant createdAt,
		long employeesCount) {

	static LegacyBranchListItem of(LegacyBranch branch, long employeesCount) {
		return new LegacyBranchListItem(
				branch.getId(), branch.getCompanyId(), branch.getName(), branch.getAddress(),
				branch.getLatitude(), branch.getLongitude(), branch.getRadiusMeters(), branch.getQrCode(),
				branch.getExpiresAt(), branch.active(), branch.getCreatedAt(), employeesCount);
	}

}
