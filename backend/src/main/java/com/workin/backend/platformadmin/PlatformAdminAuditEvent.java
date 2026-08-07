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

	protected PlatformAdminAuditEvent() {
	}

	public PlatformAdminAuditEvent(Long platformAdminId, PlatformAdminAuditEventType eventType, String detail) {
		this.platformAdminId = platformAdminId;
		this.eventType = eventType;
		this.detail = detail;
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
