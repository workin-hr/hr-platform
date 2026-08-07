package com.workin.backend.members;

import org.springframework.stereotype.Service;

import com.workin.backend.tenancy.AuthorizationContext;

/**
 * The single write path for tenant_audit_events (authorization-model.md
 * section 9). Participates in the caller's tenant-scoped transaction --
 * an audit row commits or rolls back with the mutation it records,
 * never separately.
 */
@Service
public class TenantAuditService {

	private final TenantAuditEventRepository tenantAuditEventRepository;

	public TenantAuditService(TenantAuditEventRepository tenantAuditEventRepository) {
		this.tenantAuditEventRepository = tenantAuditEventRepository;
	}

	public void record(AuthorizationContext context, Long targetMembershipId, String action,
			String permissionKey, String detail) {
		tenantAuditEventRepository.save(new TenantAuditEvent(
				context.companyId(), context.membershipId(), targetMembershipId, action, permissionKey, detail));
	}

}
