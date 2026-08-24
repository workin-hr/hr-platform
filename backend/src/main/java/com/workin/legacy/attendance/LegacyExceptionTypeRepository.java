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

	/**
	 * {@code exception_type_validate_id_for_company()}'s existence test
	 * ({@code helpers/exception_types_helper.php:47-63}), which
	 * {@code request_types} uses when resolving {@code exception_type_id}.
	 * Note the {@code is_active = 1}: an id that exists but is deactivated does
	 * not validate, and the caller stores SQL NULL rather than failing.
	 */
	boolean existsByIdAndCompanyIdAndIsActive(Long id, Long companyId, Integer isActive);

	/**
	 * {@code exception_type_resolve_for_company()}'s fallback query
	 * ({@code helpers/exception_types_helper.php:36-44}): the company's own
	 * lowest-id active row, once the caller's requested id fails to validate.
	 */
	Optional<LegacyExceptionType> findFirstByCompanyIdAndIsActiveOrderByIdAsc(Long companyId, Integer isActive);

	/** Company-scoped delete -- the row must belong to the caller's company, the same shape as legacy's own {@code DELETE ... WHERE id = ? AND company_id = ?}. */
	void deleteByIdAndCompanyId(Long id, Long companyId);

}
