package com.workin.backend.platformadmin;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformAdminAuditEventRepository extends JpaRepository<PlatformAdminAuditEvent, Long> {
}
