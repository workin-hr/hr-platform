package com.workin.backend.companysettings;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.i18n.ApiException;
import com.workin.backend.i18n.MessageKeys;
import com.workin.backend.payroll.PayrollCalculationService;
import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantSessionVariable;

/**
 * Company-settings application service. {@link #effective} is the one
 * read seam other modules use (payroll fiscal periods, leave accrual)
 * -- called inside the caller's already-established tenant transaction
 * like SalaryContractService.findEffectiveContract, which is why it
 * takes a company id, not a context.
 */
@Service
public class CompanySettingsService {

	private static final int FALLBACK_MONTH_START_DAY = 1;
	private static final BigDecimal FALLBACK_MONTHLY_LEAVE_ACCRUAL = new BigDecimal("21.0");
	private static final BigDecimal PERCENTAGE_FORM_THRESHOLD = BigDecimal.TEN;
	private static final BigDecimal PERCENTAGE_FORM_DIVISOR = BigDecimal.valueOf(100);

	private final CompanySettingsRepository companySettingsRepository;
	private final TenantSessionVariable tenantSessionVariable;

	public CompanySettingsService(
			CompanySettingsRepository companySettingsRepository, TenantSessionVariable tenantSessionVariable) {
		this.companySettingsRepository = companySettingsRepository;
		this.tenantSessionVariable = tenantSessionVariable;
	}

	@Transactional(readOnly = true)
	public CompanySettingsView view(AuthorizationContext context) {
		tenantSessionVariable.apply(context.companyId());
		return companySettingsRepository.findByCompanyId(context.companyId())
				.map(CompanySettingsView::of)
				.orElse(CompanySettingsView.UNSET);
	}

	@Transactional
	public CompanySettingsView upsert(AuthorizationContext context, UpdateCompanySettingsRequest request) {
		tenantSessionVariable.apply(context.companyId());
		CompanySettings settings = companySettingsRepository.findByCompanyId(context.companyId())
				.orElseGet(() -> new CompanySettings(context.companyId()));
		settings.apply(request);
		try {
			return CompanySettingsView.of(companySettingsRepository.saveAndFlush(settings));
		} catch (DataIntegrityViolationException ex) {
			// A lost concurrent-insert race on the UNIQUE constraint --
			// thrown, not returned (the LeaveBalanceService precedent).
			throw new ApiException(HttpStatus.CONFLICT, MessageKeys.COMPANY_SETTINGS_CONCURRENT_WRITE, ex);
		}
	}

	/**
	 * Legacy fallbacks applied for scalar reads; callers must already
	 * hold a tenant-scoped transaction (RLS confines the lookup).
	 */
	@Transactional(readOnly = true)
	public EffectiveCompanySettings effective(Long companyId) {
		Optional<CompanySettings> settings = companySettingsRepository.findByCompanyId(companyId);
		return new EffectiveCompanySettings(
				settings.map(CompanySettings::getMonthStartDay).map(Short::intValue).orElse(FALLBACK_MONTH_START_DAY),
				settings.map(CompanySettings::getMonthEndDay).map(Short::intValue).orElse(null),
				settings.map(CompanySettings::getMonthlyLeaveAccrual).orElse(FALLBACK_MONTHLY_LEAVE_ACCRUAL),
				settings.map(CompanySettings::getWeeklyOffDays)
						.map(raw -> Arrays.stream(raw.split("[,،;]+"))
								.map(String::trim).filter(s -> !s.isEmpty()).toList())
						.orElse(List.of()),
				resolveOvertimeMultiplier(settings.map(CompanySettings::getOvertimeRate).orElse(null)),
				settings.map(CompanySettings::getPayOvertime).orElse(true));
	}

	/**
	 * {@code payroll_overtime_multiplier_from_setting}
	 * (hr-legacy/apis/helpers/payroll_calculation.php:125-133): unset or
	 * non-positive falls back to the default; a value over 10 is read as
	 * a percentage ({@code 125} -> {@code 1.25}); otherwise the raw value
	 * is already a multiplier (e.g. {@code 1.5}) and is used as-is.
	 */
	private static BigDecimal resolveOvertimeMultiplier(BigDecimal raw) {
		if (raw == null || raw.signum() <= 0) {
			return PayrollCalculationService.DEFAULT_OVERTIME_MULTIPLIER;
		}
		if (raw.compareTo(PERCENTAGE_FORM_THRESHOLD) > 0) {
			return raw.divide(PERCENTAGE_FORM_DIVISOR, 4, RoundingMode.HALF_UP);
		}
		return raw;
	}

}
