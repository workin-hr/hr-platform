package com.workin.backend.penalties;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * penalty_days is stored in days, not currency -- converted to money
 * at payroll time (V13's comment). {@code applied_to_payroll} has no
 * setter here, deliberately: only the payroll module's finalize side
 * effect ever flips it, and once true this record is immutable to the
 * penalties surface (update/delete answer 409 in the service).
 */
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
	private BigDecimal penaltyDays;

	@Column
	private String reason;

	@Column(name = "penalty_date", nullable = false)
	private LocalDate penaltyDate;

	@Column(name = "applied_to_payroll", nullable = false)
	private boolean appliedToPayroll = false;

	protected Penalty() {
	}

	public Penalty(Long employeeId, Long companyId, String penaltyType, BigDecimal penaltyDays, String reason,
			LocalDate penaltyDate) {
		this.employeeId = employeeId;
		this.companyId = companyId;
		this.penaltyType = penaltyType;
		this.penaltyDays = penaltyDays;
		this.reason = reason;
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

	public BigDecimal getPenaltyDays() {
		return penaltyDays;
	}

	public String getReason() {
		return reason;
	}

	public LocalDate getPenaltyDate() {
		return penaltyDate;
	}

	public boolean isAppliedToPayroll() {
		return appliedToPayroll;
	}

	public void update(String penaltyType, BigDecimal penaltyDays, String reason, LocalDate penaltyDate) {
		this.penaltyType = penaltyType;
		this.penaltyDays = penaltyDays;
		this.reason = reason;
		if (penaltyDate != null) {
			this.penaltyDate = penaltyDate;
		}
	}

}
