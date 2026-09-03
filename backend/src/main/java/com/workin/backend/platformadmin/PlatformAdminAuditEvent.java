package com.workin.backend.platformadmin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One attributed platform-admin audit event (F-26; hr-legacy#11's
 * missing audit trail). {@code platform_admin_id} is NOT NULL by
 * design: an event that cannot be attributed to an individual admin
 * does not belong in this table.
 */
@Entity
@Table(name = "platform_admin_audit_events")
public class PlatformAdminAuditEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "platform_admin_id", nullable = false)
	private Long platformAdminId;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false)
	private PlatformAdminAuditEventType eventType;

	@Column
	private String detail;

	/**
	 * What the event was about -- "COMPANY", "PLATFORM_ADMIN" -- and which one.
	 *
	 * <p>Structured rather than prose in {@link #detail} (ADR-0015 prerequisite
	 * 10): an audit trail that cannot be filtered by subject cannot answer the
	 * question it exists for. Null for events whose subject is the actor
	 * themselves, such as a login.
	 */
	@Column(name = "target_type", length = 64)
	private String targetType;

	@Column(name = "target_id", length = 64)
	private String targetId;

	/** The step-up approval that authorised this action, once those exist. */
	@Column(name = "step_up_approval_id", length = 64)
	private String stepUpApprovalId;

	protected PlatformAdminAuditEvent() {
	}

	public PlatformAdminAuditEvent(Long platformAdminId, PlatformAdminAuditEventType eventType, String detail) {
		this(platformAdminId, eventType, detail, null, null, null);
	}

	public PlatformAdminAuditEvent(Long platformAdminId, PlatformAdminAuditEventType eventType, String detail,
			String targetType, String targetId, String stepUpApprovalId) {
		this.platformAdminId = platformAdminId;
		this.eventType = eventType;
		this.detail = detail;
		this.targetType = targetType;
		this.targetId = targetId;
		this.stepUpApprovalId = stepUpApprovalId;
	}

	public String getTargetType() {
		return targetType;
	}

	public String getTargetId() {
		return targetId;
	}

	public String getStepUpApprovalId() {
		return stepUpApprovalId;
	}

	public Long getId() {
		return id;
	}

	public Long getPlatformAdminId() {
		return platformAdminId;
	}

	public PlatformAdminAuditEventType getEventType() {
		return eventType;
	}

	public String getDetail() {
		return detail;
	}

}
