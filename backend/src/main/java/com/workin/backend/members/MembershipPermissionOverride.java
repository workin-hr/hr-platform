package com.workin.backend.members;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * V18's per-membership explicit ALLOW/DENY row. First JPA mapping of
 * this table -- PermissionEvaluationService deliberately keeps reading
 * it via native SQL and is untouched by this module.
 */
@Entity
@Table(name = "membership_permission_overrides")
public class MembershipPermissionOverride {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "membership_id", nullable = false)
	private Long membershipId;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(name = "permission_id", nullable = false)
	private Long permissionId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OverrideEffect effect;

	protected MembershipPermissionOverride() {
	}

	public MembershipPermissionOverride(Long membershipId, Long companyId, Long permissionId, OverrideEffect effect) {
		this.membershipId = membershipId;
		this.companyId = companyId;
		this.permissionId = permissionId;
		this.effect = effect;
	}

	public Long getId() {
		return id;
	}

	public Long getMembershipId() {
		return membershipId;
	}

	public Long getCompanyId() {
		return companyId;
	}

	public Long getPermissionId() {
		return permissionId;
	}

	public OverrideEffect getEffect() {
		return effect;
	}

}
