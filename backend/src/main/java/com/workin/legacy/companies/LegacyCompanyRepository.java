package com.workin.legacy.companies;

import org.springframework.data.jpa.repository.JpaRepository;

/** Reads {@link LegacyCompany} from the legacy MySQL contract. */
public interface LegacyCompanyRepository extends JpaRepository<LegacyCompany, Long> {
}
