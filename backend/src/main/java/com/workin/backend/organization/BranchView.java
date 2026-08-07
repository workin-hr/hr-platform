package com.workin.backend.organization;

import java.math.BigDecimal;
import java.time.Instant;

public record BranchView(
		Long id, String name, String address, BigDecimal latitude, BigDecimal longitude,
		int radiusMeters, String qrCode, Instant expiresAt, boolean isActive) {

	static BranchView of(Branch b) {
		return new BranchView(
				b.getId(), b.getName(), b.getAddress(), b.getLatitude(), b.getLongitude(),
				b.getRadiusMeters(), b.getQrCode(), b.getExpiresAt(), b.isActive());
	}

}
