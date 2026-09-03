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

	/**
	 * Records an administrative action, <b>in the caller's transaction</b>
	 * (ADR-0015 prerequisite 10).
	 *
	 * <p>The opposite propagation to {@link #record}, on purpose. An
	 * authentication event must survive the 401 that follows it, so it commits
	 * separately. An administrative action must do the reverse: the ADR requires
	 * "the event written in the same transaction as the action so a committed
	 * change cannot exist without its audit row". With REQUIRES_NEW, a suspension
	 * that rolled back would leave an audit row claiming it happened -- and, worse,
	 * a suspension that committed after its audit write failed would leave none.
	 *
	 * <p>{@code MANDATORY}, not {@code REQUIRED}: if a caller has no transaction,
	 * "same transaction as the action" is not a guarantee anyone can make, and
	 * failing loudly at the first call is better than discovering it in an
	 * incident review.
	 *
	 * @param targetType what the action was performed on, e.g. {@code COMPANY}
	 * @param targetId which one
	 * @param stepUpApprovalId the approval that authorised it, once step-up exists
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void recordAction(Long platformAdminId, PlatformAdminAuditEventType eventType,
			String targetType, String targetId, String stepUpApprovalId, String detail) {
		auditEventRepository.save(new PlatformAdminAuditEvent(
				platformAdminId, eventType, detail, targetType, targetId, stepUpApprovalId));
	}

}
