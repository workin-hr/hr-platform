package com.workin.backend.platformadmin.stepup;

import java.time.Instant;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

	/**
	 * {@code SELECT ... FOR UPDATE}. The single-use decision is made on this
	 * row, and the lock is what makes it a decision rather than a race.
	 *
	 * <p>Without it the service read the row, checked it, then issued a
	 * conditional {@code UPDATE ... WHERE consumed_at IS NULL} and let the
	 * database report 0 or 1 rows. PostgreSQL answers a loser with 0 rows.
	 * MariaDB 11.8, under its default snapshot isolation, answers with an
	 * error -- "Record has changed since last read; try restarting
	 * transaction" -- so the second of two concurrent submits of the same
	 * approval failed with a 500 instead of a refusal. Holding the row from
	 * the read onwards means a loser waits for the winner to commit, reads the
	 * consumed row, and refuses at the check; the UPDATE it never issues
	 * cannot conflict.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<PlatformAdminStepUpApproval> findByIdAndPlatformAdminId(String id, Long platformAdminId);

	@Modifying
	@Query("DELETE FROM PlatformAdminStepUpApproval a WHERE a.expiresAt < :cutoff")
	int deleteExpiredBefore(@Param("cutoff") Instant cutoff);

}
