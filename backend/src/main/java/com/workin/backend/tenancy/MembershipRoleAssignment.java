package com.workin.backend.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** RLS-protected: rls/V5__enable_row_level_security.sql. */
@Entity
@Table(name = "membership_roles")
public class MembershipRoleAssignment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "membership_id", nullable = false)
	private Long membershipId;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private TenantRole role;

	protected MembershipRoleAssignment() {
	}

	public MembershipRoleAssignment(Long membershipId, Long companyId, TenantRole role) {
		this.membershipId = membershipId;
		this.companyId = companyId;
		this.role = role;
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

	public TenantRole getRole() {
		return role;
	}

}
