package com.workin.spike.referencedata;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Deliberately exposes both unscoped and company-scoped read methods, so
 * the two isolation strategies can be compared honestly:
 *
 * <ul>
 *   <li>{@link RlsBranchService} uses the <b>unscoped</b> methods
 *   ({@code findAll}/{@code findById}) on purpose -- proving Postgres RLS
 *   protects tenant isolation even when application code does not
 *   explicitly filter by company_id.</li>
 *   <li>{@link GuardBranchService} uses the <b>scoped</b> methods
 *   ({@code findAllByCompanyId}/{@code findByIdAndCompanyId}) -- proving
 *   the repository-guard pattern works when application code is
 *   disciplined about always filtering.</li>
 * </ul>
 */
public interface BranchRepository extends JpaRepository<Branch, Long> {

    List<Branch> findAllByCompanyId(Long companyId);

    Optional<Branch> findByIdAndCompanyId(Long id, Long companyId);
}
