package com.workin.backend.platformadmin.stepup;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One step-up approval: a single destructive action, already second-factored. */
@Entity
@Table(name = "platform_admin_step_up_approvals")
public class PlatformAdminStepUpApproval {

	@Id
	private String id;

	@Column(name = "platform_admin_id", nullable = false)
	private Long platformAdminId;

	@Column(nullable = false, length = 64)
	private String action;

	@Column(name = "target_type", nullable = false, length = 64)
	private String targetType;

	@Column(name = "target_id", nullable = false, length = 64)
	private String targetId;

	@Column(name = "request_digest", nullable = false, length = 64)
	private String requestDigest;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "consumed_at")
	private Instant consumedAt;

	protected PlatformAdminStepUpApproval() {
	}

	public PlatformAdminStepUpApproval(String id, Long platformAdminId, String action,
			String targetType, String targetId, String requestDigest,
			Instant createdAt, Instant expiresAt) {
		this.id = id;
		this.platformAdminId = platformAdminId;
		this.action = action;
		this.targetType = targetType;
		this.targetId = targetId;
		this.requestDigest = requestDigest;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
	}

	public String getId() {
		return this.id;
	}

	public Long getPlatformAdminId() {
		return this.platformAdminId;
	}

	public String getAction() {
		return this.action;
	}

	public String getTargetType() {
		return this.targetType;
	}

	public String getTargetId() {
		return this.targetId;
	}

	public String getRequestDigest() {
		return this.requestDigest;
	}

	public Instant getExpiresAt() {
		return this.expiresAt;
	}

	public Instant getConsumedAt() {
		return this.consumedAt;
	}

}
