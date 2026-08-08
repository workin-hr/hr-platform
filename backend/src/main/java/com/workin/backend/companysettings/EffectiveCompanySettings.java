package com.workin.backend.companysettings;

import java.math.BigDecimal;
import java.util.List;

/**
 * Scalar reads with the legacy fallbacks already applied --
 * {@code monthEndDay} stays nullable because its fallback (the
 * month's last day) depends on which month the caller is computing.
 * {@code weeklyOffDays} is the split, trimmed token list from the
 * weekly_off_days column; unset resolves to an empty list (legacy's
 * schedule_company_weekly_rest_dows returns [] on empty input).
 */
public record EffectiveCompanySettings(
		int monthStartDay, Integer monthEndDay, BigDecimal monthlyLeaveAccrual, List<String> weeklyOffDays) {
}
