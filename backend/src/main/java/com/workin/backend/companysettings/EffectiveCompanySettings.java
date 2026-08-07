package com.workin.backend.companysettings;

import java.math.BigDecimal;

/**
 * Scalar reads with the legacy fallbacks already applied --
 * {@code monthEndDay} stays nullable because its fallback (the
 * month's last day) depends on which month the caller is computing.
 */
public record EffectiveCompanySettings(
		int monthStartDay, Integer monthEndDay, BigDecimal monthlyLeaveAccrual) {
}
