package com.workin.backend.authorization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A membership's resource scope (V31): scope_id references a branch or
 * department by scope_type. Enforcement-boundary-3 data
 * (docs/architecture/authorization-model.md section 3).
 */
@Entity
@Table(name = "membership_resource_scopes")
public class ResourceScope {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "membership_id", nullable = false)
	private Long membershipId;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Enumerated(EnumType.STRING)
	@Column(name = "scope_type", nullable = false)
	private ResourceScopeType scopeType;

	@Column(name = "scope_id", nullable = false)
	private Long scopeId;

	protected ResourceScope() {
	}

	public ResourceScope(Long membershipId, Long companyId, ResourceScopeType scopeType, Long scopeId) {
		this.membershipId = membershipId;
		this.companyId = companyId;
		this.scopeType = scopeType;
		this.scopeId = scopeId;
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

	public ResourceScopeType getScopeType() {
		return scopeType;
	}

	public Long getScopeId() {
		return scopeId;
	}

}
