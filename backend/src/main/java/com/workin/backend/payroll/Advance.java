package com.workin.backend.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "advances")
public class Advance {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "employee_id", nullable = false)
	private Long employeeId;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(nullable = false)
	private BigDecimal amount;

	@Column(nullable = false)
	private BigDecimal remaining;

	@Column
	private String reason;

	@Enumerated(EnumType.STRING)
	@Column(name = "deduction_mode", nullable = false)
	private DeductionMode deductionMode = DeductionMode.SINGLE_PAYROLL_MONTH;

	@Column(name = "deduction_month_count", nullable = false)
	private int deductionMonthCount = 1;

	@Column(name = "deduction_amount_per_month")
	private BigDecimal deductionAmountPerMonth;

	@Column(name = "deduction_payroll_year")
	private Short deductionPayrollYear;

	@Column(name = "deduction_payroll_month")
	private Short deductionPayrollMonth;

	@Column(name = "rejection_reason")
	private String rejectionReason;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private AdvanceStatus status = AdvanceStatus.PENDING;

	@Column(name = "request_date", nullable = false)
	private LocalDate requestDate;

	protected Advance() {
	}

	public Advance(Long employeeId, Long companyId, BigDecimal amount, LocalDate requestDate) {
		this.employeeId = employeeId;
		this.companyId = companyId;
		this.amount = amount;
		this.remaining = amount;
		this.requestDate = requestDate;
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

	public BigDecimal getAmount() {
		return amount;
	}

	public void setAmount(BigDecimal amount) {
		this.amount = amount;
	}

	public BigDecimal getRemaining() {
		return remaining;
	}

	public void setRemaining(BigDecimal remaining) {
		this.remaining = remaining;
	}

	public String getReason() {
		return reason;
	}

	public void setReason(String reason) {
		this.reason = reason;
	}

	public DeductionMode getDeductionMode() {
		return deductionMode;
	}

	public void setDeductionMode(DeductionMode deductionMode) {
		this.deductionMode = deductionMode;
	}

	public int getDeductionMonthCount() {
		return deductionMonthCount;
	}

	public void setDeductionMonthCount(int deductionMonthCount) {
		this.deductionMonthCount = deductionMonthCount;
	}

	public BigDecimal getDeductionAmountPerMonth() {
		return deductionAmountPerMonth;
	}

	public void setDeductionAmountPerMonth(BigDecimal deductionAmountPerMonth) {
		this.deductionAmountPerMonth = deductionAmountPerMonth;
	}

	public Short getDeductionPayrollYear() {
		return deductionPayrollYear;
	}

	public void setDeductionPayrollYear(Short deductionPayrollYear) {
		this.deductionPayrollYear = deductionPayrollYear;
	}

	public Short getDeductionPayrollMonth() {
		return deductionPayrollMonth;
	}

	public void setDeductionPayrollMonth(Short deductionPayrollMonth) {
		this.deductionPayrollMonth = deductionPayrollMonth;
	}

	public String getRejectionReason() {
		return rejectionReason;
	}

	public void setRejectionReason(String rejectionReason) {
		this.rejectionReason = rejectionReason;
	}

	public AdvanceStatus getStatus() {
		return status;
	}

	public void setStatus(AdvanceStatus status) {
		this.status = status;
	}

	public LocalDate getRequestDate() {
		return requestDate;
	}

}
