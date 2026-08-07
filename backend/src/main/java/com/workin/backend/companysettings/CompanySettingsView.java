package com.workin.backend.companysettings;

import java.math.BigDecimal;

/** Raw stored values -- fallbacks are the consumers' business (see EffectiveCompanySettings). */
public record CompanySettingsView(
		Short monthStartDay, Short monthEndDay, String weeklyOffDays,
		BigDecimal overtimeRate, BigDecimal monthlyLeaveAccrual) {

	static final CompanySettingsView UNSET = new CompanySettingsView(null, null, null, null, null);

	static CompanySettingsView of(CompanySettings s) {
		return new CompanySettingsView(
				s.getMonthStartDay(), s.getMonthEndDay(), s.getWeeklyOffDays(),
				s.getOvertimeRate(), s.getMonthlyLeaveAccrual());
	}

}
