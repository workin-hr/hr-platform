package com.workin.backend.advances;

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

/**
 * Maps the columns this slice's surface uses; the deduction_* columns
 * stay unmapped and keep their V12 schema defaults -- V12's own
 * comment parks the legacy deduction_type redundancy as a product
 * decision before the business-logic slice. {@code remaining} is set
 * to {@code amount} at creation and mutated only by {@link #deduct} --
 * applying deductions is the payroll module's finalize side effect
 * (see this class's own {@link AdvanceStatus} Javadoc).
 */
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

	@Column(name = "rejection_reason")
	private String rejectionReason;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private AdvanceStatus status = AdvanceStatus.PENDING;

	@Column(name = "request_date", nullable = false)
	private LocalDate requestDate;

	protected Advance() {
	}

	public Advance(Long employeeId, Long companyId, BigDecimal amount, String reason, LocalDate requestDate) {
		this.employeeId = employeeId;
		this.companyId = companyId;
		this.amount = amount;
		this.remaining = amount;
		this.reason = reason;
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

	public BigDecimal getRemaining() {
		return remaining;
	}

	public String getReason() {
		return reason;
	}

	public String getRejectionReason() {
		return rejectionReason;
	}

	public AdvanceStatus getStatus() {
		return status;
	}

	public LocalDate getRequestDate() {
		return requestDate;
	}

	public boolean isPending() {
		return status == AdvanceStatus.PENDING;
	}

	public void approve() {
		requirePending();
		this.status = AdvanceStatus.APPROVED;
	}

	public void reject(String rejectionReason) {
		requirePending();
		this.status = AdvanceStatus.REJECTED;
		this.rejectionReason = rejectionReason;
	}

	private void requirePending() {
		if (!isPending()) {
			// Belt-and-braces: the service answers 409 before reaching
			// here; this guard keeps the invariant even for future
			// callers that forget to check.
			throw new IllegalStateException("Advance " + id + " is not PENDING");
		}
	}

	/**
	 * Reduces {@code remaining} by a payroll finalize deduction --
	 * the only mutation path for this field. Caps at zero rather than
	 * throwing on an over-large request; the caller (payroll finalize)
	 * is responsible for not requesting more than {@link #getRemaining}
	 * across the advances it applies to a given payslip.
	 */
	public void deduct(BigDecimal deductionAmount) {
		BigDecimal applied = deductionAmount.min(remaining);
		this.remaining = remaining.subtract(applied);
	}

	/** Reverses a previously applied {@link #deduct} amount -- payroll reopen's side effect. */
	public void restore(BigDecimal restoredAmount) {
		this.remaining = remaining.add(restoredAmount).min(amount);
	}

}
