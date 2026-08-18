package com.workin.legacy.employees;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads {@link LegacyEmployee} from the legacy MySQL contract.
 *
 * <p>Every finder here takes {@code companyId} explicitly. Phase 1 has
 * no PostgreSQL row-level security to fall back on, so tenant scoping
 * stops being a database guarantee and becomes an application
 * invariant -- and the way that invariant is kept honest is by making a
 * company-blind query impossible to write by accident.
 * {@link #findByPhone} is the one deliberate exception, because login
 * has to resolve a phone before any tenant is known; its javadoc says so
 * and its shape (a list, not an optional) is legacy's own.
 */
public interface LegacyEmployeeRepository extends JpaRepository<LegacyEmployee, Long> {

	Optional<LegacyEmployee> findByIdAndCompanyId(Long id, Long companyId);

	List<LegacyEmployee> findByCompanyId(Long companyId);

	/**
	 * Every employee row owning this phone, newest first.
	 *
	 * <p>Deliberately company-blind and deliberately a {@code List}:
	 * this is the pre-authentication lookup, and legacy's own login runs
	 * exactly this query with {@code ORDER BY e.id DESC}, then filters
	 * the results and rejects a multi-match with **409
	 * MULTIPLE_ACCOUNTS_SAME_PHONE**
	 * (hr-legacy/apis/api/auth/login_employee.php:18-48, :105-107).
	 *
	 * <p>Returning a list rather than an optional is the whole point.
	 * Collapsing it to one row would erase the condition legacy reports,
	 * and reproducing that 409 is a Phase 1 parity requirement -- the
	 * multi-tenant identity model that removes it is Phase 3.
	 */
	List<LegacyEmployee> findByPhoneOrderByIdDesc(String phone);

	/**
	 * Legacy's {@code employee_issue_session_token()} bump (P-7, D-049):
	 * invalidates any previously issued token for this employee by
	 * advancing the version a fresh login's token embeds. A bulk update,
	 * not a load-mutate-save, for the same reason
	 * {@code LegacyRefreshTokenRepository.transitionIfStatus} is one --
	 * this runs pre-tenant (login), where no scope is established yet,
	 * and a bulk HQL update bypasses the {@code @Filter} entirely rather
	 * than depending on it being disabled first.
	 *
	 * <p>{@code clearAutomatically}: the caller re-reads this row
	 * ({@code findById}) immediately after to embed the new value in the
	 * issued token, and without this the persistence context would still
	 * hand back the pre-bump entity from its first-level cache.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update LegacyEmployee e set e.tokenVersion = e.tokenVersion + 1 where e.id = :id")
	int bumpTokenVersion(@Param("id") Long id);

	/**
	 * {@code branch_assigned_employees_count()} (PR 12.3a, D-056): any
	 * employee row referencing the branch, active or not, join-request
	 * status irrelevant -- the pre-check that blocks a branch's hard
	 * delete. Deliberately not the active-roster count below; legacy
	 * itself uses two different counts for two different questions.
	 */
	long countByBranchIdAndCompanyId(Long branchId, Long companyId);

	/**
	 * {@code branch_active_employees_count()} / {@code list.php}'s
	 * per-row {@code employees_count} (PR 12.3a): accepted-roster
	 * ({@code COALESCE(join_request_status, 'accepted') = 'accepted'})
	 * and active. Native, not JPQL: {@code COALESCE} against a raw
	 * column, the same one-hop shape {@code sql_employee_roster_join_clause()}
	 * builds in PHP.
	 */
	@Query(value = "SELECT COUNT(*) FROM employees WHERE branch_id = :branchId AND company_id = :companyId "
			+ "AND COALESCE(join_request_status, 'accepted') = 'accepted' AND is_active = 1", nativeQuery = true)
	long countActiveRosterByBranchIdAndCompanyId(@Param("branchId") Long branchId, @Param("companyId") Long companyId);

}
