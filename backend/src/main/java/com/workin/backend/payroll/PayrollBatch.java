package com.workin.backend.payroll;

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
@Table(name = "payroll_batches")
public class PayrollBatch {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(nullable = false)
	private short month;

	@Column(nullable = false)
	private short year;

	@Column(name = "period_from", nullable = false)
	private LocalDate periodFrom;

	@Column(name = "period_to", nullable = false)
	private LocalDate periodTo;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private BatchStatus status = BatchStatus.DRAFT;

	protected PayrollBatch() {
	}

	public PayrollBatch(Long companyId, short month, short year, LocalDate periodFrom, LocalDate periodTo) {
		this.companyId = companyId;
		this.month = month;
		this.year = year;
		this.periodFrom = periodFrom;
		this.periodTo = periodTo;
	}

	public Long getId() {
		return id;
	}

	public Long getCompanyId() {
		return companyId;
	}

	public short getMonth() {
		return month;
	}

	public short getYear() {
		return year;
	}

	public LocalDate getPeriodFrom() {
		return periodFrom;
	}

	public void setPeriodFrom(LocalDate periodFrom) {
		this.periodFrom = periodFrom;
	}

	public LocalDate getPeriodTo() {
		return periodTo;
	}

	public void setPeriodTo(LocalDate periodTo) {
		this.periodTo = periodTo;
	}

	public BatchStatus getStatus() {
		return status;
	}

	public void setStatus(BatchStatus status) {
		this.status = status;
	}

}
