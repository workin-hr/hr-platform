package com.workin.backend.platformadmin;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single write path for platform-admin audit attribution (F-26).
 * Every future {@code platform.*} business endpoint must record its
 * action here -- a standing acceptance criterion, enforceable by the
 * same ArchUnit mechanism as F-23 once that lands.
 *
 * <p>REQUIRES_NEW so an audit row commits even when the surrounding
 * business transaction ends in a rollback or the request answers 401 --
 * an audit trail that disappears with the failed action it recorded
 * would be no audit trail at all.
 */
@Service
public class PlatformAdminAuditService {

	private final PlatformAdminAuditEventRepository auditEventRepository;

	public PlatformAdminAuditService(PlatformAdminAuditEventRepository auditEventRepository) {
		this.auditEventRepository = auditEventRepository;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void record(Long platformAdminId, PlatformAdminAuditEventType eventType, String detail) {
		auditEventRepository.save(new PlatformAdminAuditEvent(platformAdminId, eventType, detail));
	}

}
