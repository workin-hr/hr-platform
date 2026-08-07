package com.workin.backend.companysettings;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanySettingsRepository extends JpaRepository<CompanySettings, Long> {

	Optional<CompanySettings> findByCompanyId(Long companyId);

}
