package com.workin.backend.companysettings;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row per company (V27's real UNIQUE constraint), five typed
 * columns replacing legacy's EAV. Every field is nullable with null
 * meaning "unset -- apply the legacy fallback" (start 1, end = the
 * month's last day, accrual 21.0), so a company with no row behaves
 * exactly as before this table existed.
 */
@Entity
@Table(name = "company_settings")
public class CompanySettings {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(name = "month_start_day")
	private Short monthStartDay;

	@Column(name = "month_end_day")
	private Short monthEndDay;

	@Column(name = "weekly_off_days")
	private String weeklyOffDays;

	@Column(name = "overtime_rate")
	private BigDecimal overtimeRate;

	@Column(name = "monthly_leave_accrual")
	private BigDecimal monthlyLeaveAccrual;

	@Column(name = "pay_overtime")
	private Boolean payOvertime;

	protected CompanySettings() {
	}

	public CompanySettings(Long companyId) {
		this.companyId = companyId;
	}

	/** Nulls included -- null is a real value ("unset this"). */
	public void apply(UpdateCompanySettingsRequest request) {
		this.monthStartDay = request.monthStartDay();
		this.monthEndDay = request.monthEndDay();
		this.weeklyOffDays = request.weeklyOffDays();
		this.overtimeRate = request.overtimeRate();
		this.monthlyLeaveAccrual = request.monthlyLeaveAccrual();
		this.payOvertime = request.payOvertime();
	}

	public Long getId() {
		return id;
	}

	public Long getCompanyId() {
		return companyId;
	}

	public Short getMonthStartDay() {
		return monthStartDay;
	}

	public Short getMonthEndDay() {
		return monthEndDay;
	}

	public String getWeeklyOffDays() {
		return weeklyOffDays;
	}

	public BigDecimal getOvertimeRate() {
		return overtimeRate;
	}

	public BigDecimal getMonthlyLeaveAccrual() {
		return monthlyLeaveAccrual;
	}

	public Boolean getPayOvertime() {
		return payOvertime;
	}

}
