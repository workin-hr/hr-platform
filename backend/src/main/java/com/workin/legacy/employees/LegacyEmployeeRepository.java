package com.workin.legacy.employees;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

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

}
