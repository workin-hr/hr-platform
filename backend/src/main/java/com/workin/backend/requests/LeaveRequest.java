package com.workin.backend.requests;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A leave/permission request (V25's requests table -- class named to
 * avoid the servlet Request clash). Always born PENDING; decided
 * exactly once via {@link #decide}; pending-only for edits, enforced
 * in {@link RequestService}.
 */
@Entity
@Table(name = "requests")
public class LeaveRequest {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "employee_id", nullable = false)
	private Long employeeId;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(name = "request_type_id", nullable = false)
	private Long requestTypeId;

	@Column(name = "from_date", nullable = false)
	private LocalDate fromDate;

	@Column(name = "to_date", nullable = false)
	private LocalDate toDate;

	@Column(name = "from_time")
	private LocalTime fromTime;

	@Column(name = "to_time")
	private LocalTime toTime;

	@Column
	private String notes;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private RequestStatus status = RequestStatus.PENDING;

	@Column
	private String reply;

	@Column(name = "approver_membership_id")
	private Long approverMembershipId;

	@Column(name = "decided_at")
	private Instant decidedAt;

	protected LeaveRequest() {
	}

	public LeaveRequest(Long employeeId, Long companyId, Long requestTypeId,
			LocalDate fromDate, LocalDate toDate, LocalTime fromTime, LocalTime toTime, String notes) {
		this.employeeId = employeeId;
		this.companyId = companyId;
		this.requestTypeId = requestTypeId;
		this.fromDate = fromDate;
		this.toDate = toDate;
		this.fromTime = fromTime;
		this.toTime = toTime;
		this.notes = notes;
	}

	public void updateDetails(Long requestTypeId, LocalDate fromDate, LocalDate toDate,
			LocalTime fromTime, LocalTime toTime, String notes) {
		this.requestTypeId = requestTypeId;
		this.fromDate = fromDate;
		this.toDate = toDate;
		this.fromTime = fromTime;
		this.toTime = toTime;
		this.notes = notes;
	}

	public void decide(RequestStatus status, String reply, Long approverMembershipId, Instant decidedAt) {
		this.status = status;
		this.reply = reply;
		this.approverMembershipId = approverMembershipId;
		this.decidedAt = decidedAt;
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

	public Long getRequestTypeId() {
		return requestTypeId;
	}

	public LocalDate getFromDate() {
		return fromDate;
	}

	public LocalDate getToDate() {
		return toDate;
	}

	public LocalTime getFromTime() {
		return fromTime;
	}

	public LocalTime getToTime() {
		return toTime;
	}

	public String getNotes() {
		return notes;
	}

	public RequestStatus getStatus() {
		return status;
	}

	public String getReply() {
		return reply;
	}

	public Long getApproverMembershipId() {
		return approverMembershipId;
	}

	public Instant getDecidedAt() {
		return decidedAt;
	}

}
