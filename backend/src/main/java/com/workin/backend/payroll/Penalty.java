package com.workin.backend.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "penalties")
public class Penalty {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "employee_id", nullable = false)
	private Long employeeId;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(name = "penalty_type", nullable = false)
	private String penaltyType;

	@Column(name = "penalty_days", nullable = false)
	private BigDecimal penaltyDays = BigDecimal.ZERO;

	@Column
	private String reason;

	@Column(name = "penalty_date", nullable = false)
	private LocalDate penaltyDate;

	@Column(name = "applied_to_payroll", nullable = false)
	private boolean appliedToPayroll = false;

	protected Penalty() {
	}

	public Penalty(Long employeeId, Long companyId, String penaltyType, BigDecimal penaltyDays, LocalDate penaltyDate) {
		this.employeeId = employeeId;
		this.companyId = companyId;
		this.penaltyType = penaltyType;
		this.penaltyDays = penaltyDays;
		this.penaltyDate = penaltyDate;
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

	public String getPenaltyType() {
		return penaltyType;
	}

	public void setPenaltyType(String penaltyType) {
		this.penaltyType = penaltyType;
	}

	public BigDecimal getPenaltyDays() {
		return penaltyDays;
	}

	public void setPenaltyDays(BigDecimal penaltyDays) {
		this.penaltyDays = penaltyDays;
	}

	public String getReason() {
		return reason;
	}

	public void setReason(String reason) {
		this.reason = reason;
	}

	public LocalDate getPenaltyDate() {
		return penaltyDate;
	}

	public void setPenaltyDate(LocalDate penaltyDate) {
		this.penaltyDate = penaltyDate;
	}

	public boolean isAppliedToPayroll() {
		return appliedToPayroll;
	}

	public void setAppliedToPayroll(boolean appliedToPayroll) {
		this.appliedToPayroll = appliedToPayroll;
	}

}
