package com.workin.backend.platformadmin.stepup;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlatformAdminStepUpApprovalRepository
		extends JpaRepository<PlatformAdminStepUpApproval, String> {

	/**
	 * Consumes an approval, atomically.
	 *
	 * <p>A read-then-write would let two concurrent requests both see an unspent
	 * approval and both proceed -- which is precisely what "single use" exists
	 * to prevent, and precisely the shape that only shows up under load. The
	 * {@code consumed_at IS NULL} predicate makes the database decide the race,
	 * and the returned row count says who won.
	 */
	@Modifying
	@Query("UPDATE PlatformAdminStepUpApproval a SET a.consumedAt = :now "
			+ "WHERE a.id = :id AND a.consumedAt IS NULL")
	int consume(@Param("id") String id, @Param("now") Instant now);

	Optional<PlatformAdminStepUpApproval> findByIdAndPlatformAdminId(String id, Long platformAdminId);

	@Modifying
	@Query("DELETE FROM PlatformAdminStepUpApproval a WHERE a.expiresAt < :cutoff")
	int deleteExpiredBefore(@Param("cutoff") Instant cutoff);

}
