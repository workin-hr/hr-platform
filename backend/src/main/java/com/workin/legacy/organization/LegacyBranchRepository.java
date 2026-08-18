package com.workin.legacy.organization;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Reads/writes {@link LegacyBranch} under the P-1a tenant filter. */
public interface LegacyBranchRepository extends JpaRepository<LegacyBranch, Long> {

	Optional<LegacyBranch> findByIdAndCompanyId(Long id, Long companyId);

	Page<LegacyBranch> findByCompanyIdAndIsActive(Long companyId, Integer isActive, Pageable pageable);

	/**
	 * {@code list.php}'s search branch: {@code (name LIKE ? OR address
	 * LIKE ?)} -- a derived {@code ...AndOr...} method name would parse
	 * left-to-right with no grouping ({@code (companyId AND isActive AND
	 * name) OR address}, not what legacy's SQL does), so this is an
	 * explicit JPQL query instead.
	 */
	@Query("select b from LegacyBranch b where b.companyId = :companyId and b.isActive = :isActive "
			+ "and (b.name like :namePart or b.address like :addressPart)")
	Page<LegacyBranch> searchByCompanyIdAndIsActive(
			@Param("companyId") Long companyId, @Param("isActive") Integer isActive,
			@Param("namePart") String namePart, @Param("addressPart") String addressPart, Pageable pageable);

	/** Company-scoped hard delete -- the same shape as legacy's own {@code DELETE ... WHERE id = ? AND company_id = ?}. */
	void deleteByIdAndCompanyId(Long id, Long companyId);

}
