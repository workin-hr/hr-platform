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
	private final org.springframework.jdbc.core.JdbcTemplate jdbc;

	public PostgresPlatformAdminCompanyDirectory(CompanyRepository companyRepository,
			javax.sql.DataSource dataSource) {
		this.companyRepository = companyRepository;
		this.jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
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
	public java.util.Optional<CompanyDetail> detail(long companyId) {
		return this.companyRepository.findById(companyId).map(company -> new CompanyDetail(
				new CompanyView(company.getId(), company.getName(), company.getStatus()),
				company.getRejectionReason(),
				// company_id is on the row here, so no join through employees --
				// the legacy schema is the one that needs it. Status is upper
				// case on this side and lower case on legacy's; that difference
				// is the reason these queries are not shared.
				count("SELECT COUNT(*) FROM requests WHERE company_id = ? AND status = 'PENDING'", companyId),
				count("SELECT COUNT(*) FROM advances WHERE company_id = ? AND status = 'PENDING'", companyId)));
	}

	private long count(String sql, long companyId) {
		Long value = this.jdbc.queryForObject(sql, Long.class, companyId);
		return value == null ? 0L : value;
	}

	@Override
	public boolean reject(long companyId, String reason) {
		return this.companyRepository.findById(companyId)
			.map(company -> {
				company.setStatus("rejected");
				company.setRejectionReason(reason);
				return true;
			})
			.orElse(false);
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
