package com.workin.backend.members;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantAuditEventRepository extends JpaRepository<TenantAuditEvent, Long> {
}
