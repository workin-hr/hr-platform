package com.workin.legacy.attendance;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Reads/writes {@link LegacyExceptionType} under the P-1a tenant filter. */
public interface LegacyExceptionTypeRepository extends JpaRepository<LegacyExceptionType, Long> {

	Optional<LegacyExceptionType> findByIdAndCompanyId(Long id, Long companyId);

	/**
	 * The same-company fast path: a clean, pre-emptive 409 for the
	 * common case, without a round trip through the database. Uniqueness
	 * itself is global (D-7/D-051) -- a cross-company collision this
	 * check cannot see is still caught by the real {@code
	 * unique_exception_type_name} constraint, via {@link
	 * LegacyExceptionTypeService#saveOrConflict}.
	 */
	boolean existsByCompanyIdAndName(Long companyId, String name);

	/** The fast path's update-side check, excluding the row being renamed. */
	boolean existsByCompanyIdAndNameAndIdNot(Long companyId, String name, Long id);

	Page<LegacyExceptionType> findByCompanyId(Long companyId, Pageable pageable);

	Page<LegacyExceptionType> findByCompanyIdAndIsActive(Long companyId, Integer isActive, Pageable pageable);

	Page<LegacyExceptionType> findByCompanyIdAndNameContaining(Long companyId, String namePart, Pageable pageable);

	Page<LegacyExceptionType> findByCompanyIdAndIsActiveAndNameContaining(
			Long companyId, Integer isActive, String namePart, Pageable pageable);

	/** Company-scoped delete -- the row must belong to the caller's company, the same shape as legacy's own {@code DELETE ... WHERE id = ? AND company_id = ?}. */
	void deleteByIdAndCompanyId(Long id, Long companyId);

}
