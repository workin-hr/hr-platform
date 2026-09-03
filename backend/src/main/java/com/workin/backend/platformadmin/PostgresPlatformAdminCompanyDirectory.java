package com.workin.backend.platformadmin;

import java.util.Comparator;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.workin.backend.identity.Company;
import com.workin.backend.identity.CompanyRepository;

/** The PostgreSQL domain's companies. */
@Component
@Profile("!phase1-mysql")
public class PostgresPlatformAdminCompanyDirectory implements PlatformAdminCompanyDirectory {

	private final CompanyRepository companyRepository;

	public PostgresPlatformAdminCompanyDirectory(CompanyRepository companyRepository) {
		this.companyRepository = companyRepository;
	}

	@Override
	public List<CompanyView> list(int limit) {
		return this.companyRepository.findAll().stream()
			.sorted(Comparator.comparing(Company::getId))
			.limit(limit)
			.map(company -> new CompanyView(company.getId(), company.getName(), company.getStatus()))
			.toList();
	}

	@Override
	public boolean updateStatus(long companyId, String status) {
		// Dirty checking inside the caller's transaction, so the change and its
		// audit row commit together.
		return this.companyRepository.findById(companyId)
			.map(company -> {
				company.setStatus(status);
				return true;
			})
			.orElse(false);
	}

}
