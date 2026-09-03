package com.workin.legacy.companies;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Reads {@link LegacyCompany} from the legacy MySQL contract. */
public interface LegacyCompanyRepository extends JpaRepository<LegacyCompany, Long> {

	/**
	 * The platform-admin surface's company list (ADR-0009 Option E).
	 *
	 * <p>Bounded by the caller rather than returning the table: this is an
	 * oversight list, and production has hundreds of companies.
	 */
	@Query("SELECT c FROM LegacyCompany c ORDER BY c.id")
	List<LegacyCompany> findAllOrderedById(org.springframework.data.domain.Limit limit);

	/**
	 * Sets one company's lifecycle status.
	 *
	 * <p>A statement rather than a setter on the entity: the entity is a read
	 * mapping for the frozen schema, and the platform-admin surface is the only
	 * writer of this column.
	 */
	@Modifying
	@Query("UPDATE LegacyCompany c SET c.status = :status WHERE c.id = :id")
	int updateStatus(@Param("id") long id, @Param("status") String status);

	/**
	 * Rejects a company and records why, in one statement.
	 *
	 * <p>Both columns together, matching the PHP dashboard's reject action,
	 * which writes {@code status} and {@code rejection_reason} in a single
	 * update. Splitting them would allow a state where a company is rejected
	 * with no reason, or carries a reason it was never rejected for.
	 */
	@Modifying
	@Query("UPDATE LegacyCompany c SET c.status = 'rejected', c.rejectionReason = :reason WHERE c.id = :id")
	int reject(@Param("id") long id, @Param("reason") String reason);
}
