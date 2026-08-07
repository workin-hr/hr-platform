package com.workin.backend.requests;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Per-employee, per-year leave allotment (V25). remaining_days is the
 * transcribed DB-generated column (total - used), mapped read-only.
 * used_days has no setter beyond {@link #addUsedDays}: it is written
 * only by the request-approval side effect, never directly through
 * the leave-balances surface (recorded slice decision).
 */
@Entity
@Table(name = "leave_balances")
public class LeaveBalance {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "employee_id", nullable = false)
	private Long employeeId;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(nullable = false)
	private Short year;

	@Column(name = "period_from_month", nullable = false)
	private Short periodFromMonth = 1;

	@Column(name = "period_to_month", nullable = false)
	private Short periodToMonth = 12;

	@Column(name = "monthly_cap_days")
	private BigDecimal monthlyCapDays;

	@Column(name = "total_days", nullable = false)
	private BigDecimal totalDays = BigDecimal.ZERO;

	@Column(name = "used_days", nullable = false)
	private BigDecimal usedDays = BigDecimal.ZERO;

	@Column(name = "remaining_days", insertable = false, updatable = false)
	private BigDecimal remainingDays;

	protected LeaveBalance() {
	}

	public LeaveBalance(Long employeeId, Long companyId, Short year) {
		this.employeeId = employeeId;
		this.companyId = companyId;
		this.year = year;
	}

	public void applySettings(BigDecimal totalDays, Short periodFromMonth, Short periodToMonth, BigDecimal monthlyCapDays) {
		this.totalDays = totalDays;
		if (periodFromMonth != null) {
			this.periodFromMonth = periodFromMonth;
		}
		if (periodToMonth != null) {
			this.periodToMonth = periodToMonth;
		}
		this.monthlyCapDays = monthlyCapDays;
	}

	/** The request-approval deduction side effect -- see class Javadoc. */
	public void addUsedDays(BigDecimal days) {
		this.usedDays = this.usedDays.add(days);
	}

	public Long getId() {
		return id;
	}

	public Long getEmployeeId() {
		return employeeId;
	}

	public Long getCompanyId() {
		return companyId;
	}

	public Short getYear() {
		return year;
	}

	public Short getPeriodFromMonth() {
		return periodFromMonth;
	}

	public Short getPeriodToMonth() {
		return periodToMonth;
	}

	public BigDecimal getMonthlyCapDays() {
		return monthlyCapDays;
	}

	public BigDecimal getTotalDays() {
		return totalDays;
	}

	public BigDecimal getUsedDays() {
		return usedDays;
	}

	public BigDecimal getRemainingDays() {
		return remainingDays;
	}

}
