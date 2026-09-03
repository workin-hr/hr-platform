package com.workin.backend.platformadmin;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.workin.legacy.companies.LegacyCompanyRepository;

/**
 * The legacy MySQL {@code companies} table -- the same rows the PHP dashboard's
 * {@code pages/companies/} reads and writes.
 *
 * <p>Updates through an explicit statement rather than by mutating the entity.
 * {@code LegacyCompany} is a read mapping for the frozen schema and has no
 * setters on purpose; giving it one so the admin surface could dirty-check
 * would make every other reader of that entity mutable too, which is a larger
 * change than this needs.
 */
@Component
@Profile("phase1-mysql")
public class LegacyPlatformAdminCompanyDirectory implements PlatformAdminCompanyDirectory {

	private final LegacyCompanyRepository companyRepository;

	public LegacyPlatformAdminCompanyDirectory(LegacyCompanyRepository companyRepository) {
		this.companyRepository = companyRepository;
	}

	@Override
	public List<CompanyView> list(int limit) {
		return this.companyRepository.findAllOrderedById(org.springframework.data.domain.Limit.of(limit)).stream()
			.map(row -> new CompanyView(row.getId(), row.getName(), row.getStatus()))
			.toList();
	}

	@Override
	public boolean updateStatus(long companyId, String status) {
		return this.companyRepository.updateStatus(companyId, status) == 1;
	}

}
