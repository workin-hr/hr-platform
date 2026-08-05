package com.workin.backend.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The anchor of the authorization model
 * (docs/architecture/authorization-model.md) -- one row per (identity,
 * tenant) pair. RLS-protected: rls/V5__enable_row_level_security.sql.
 */
@Entity
@Table(name = "tenant_memberships")
public class TenantMembership {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "identity_id", nullable = false)
	private Long identityId;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private MembershipStatus status = MembershipStatus.ACTIVE;

	protected TenantMembership() {
	}

	public TenantMembership(Long identityId, Long companyId) {
		this.identityId = identityId;
		this.companyId = companyId;
	}

	public Long getId() {
		return id;
	}

	public Long getIdentityId() {
		return identityId;
	}

	public Long getCompanyId() {
		return companyId;
	}

	public MembershipStatus getStatus() {
		return status;
	}

	public boolean isActive() {
		return status == MembershipStatus.ACTIVE;
	}

}
