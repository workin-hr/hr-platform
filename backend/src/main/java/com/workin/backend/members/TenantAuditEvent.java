package com.workin.backend.members;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Section-9 tenant-domain audit record (V23), the tenant counterpart
 * of platform_admin_audit_events: actor attribution is a real
 * membership FK, never free text. Written only through
 * {@link TenantAuditService}.
 */
@Entity
@Table(name = "tenant_audit_events")
public class TenantAuditEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(name = "actor_membership_id", nullable = false)
	private Long actorMembershipId;

	@Column(name = "target_membership_id")
	private Long targetMembershipId;

	@Column(nullable = false)
	private String action;

	@Column(name = "permission_key")
	private String permissionKey;

	@Column
	private String detail;

	@Column(name = "occurred_at", insertable = false, updatable = false)
	private Instant occurredAt;

	protected TenantAuditEvent() {
	}

	public TenantAuditEvent(Long companyId, Long actorMembershipId, Long targetMembershipId,
			String action, String permissionKey, String detail) {
		this.companyId = companyId;
		this.actorMembershipId = actorMembershipId;
		this.targetMembershipId = targetMembershipId;
		this.action = action;
		this.permissionKey = permissionKey;
		this.detail = detail;
	}

	public Long getId() {
		return id;
	}

	public Long getCompanyId() {
		return companyId;
	}

	public Long getActorMembershipId() {
		return actorMembershipId;
	}

	public Long getTargetMembershipId() {
		return targetMembershipId;
	}

	public String getAction() {
		return action;
	}

	public String getPermissionKey() {
		return permissionKey;
	}

	public String getDetail() {
		return detail;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}

}
