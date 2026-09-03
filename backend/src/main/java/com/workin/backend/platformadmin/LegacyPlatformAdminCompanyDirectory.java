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
	private final org.springframework.jdbc.core.JdbcTemplate jdbc;

	public LegacyPlatformAdminCompanyDirectory(LegacyCompanyRepository companyRepository,
			@org.springframework.beans.factory.annotation.Qualifier("legacyDataSource")
			javax.sql.DataSource legacyDataSource) {
		this.companyRepository = companyRepository;
		this.jdbc = new org.springframework.jdbc.core.JdbcTemplate(legacyDataSource);
	}

	@Override
	public List<CompanyView> list(int limit) {
		return this.companyRepository.findAllOrderedById(org.springframework.data.domain.Limit.of(limit)).stream()
			.map(row -> new CompanyView(row.getId(), row.getName(), row.getStatus()))
			.toList();
	}

	@Override
	public java.util.Optional<CompanyDetail> detail(long companyId) {
		return this.companyRepository.findById(companyId).map(company -> new CompanyDetail(
				new CompanyView(company.getId(), company.getName(), company.getStatus()),
				this.jdbc.queryForObject(
						"SELECT rejection_reason FROM companies WHERE id = ?", String.class, companyId),
				// Legacy's requests and advances carry no company_id, so the
				// scope comes through employees -- the same join
				// dashboard/pages/companies/detail.php uses. Status is lower
				// case here and upper case on PostgreSQL.
				count("SELECT COUNT(*) FROM requests r JOIN employees e ON e.id = r.employee_id "
						+ "WHERE e.company_id = ? AND r.status = 'pending'", companyId),
				count("SELECT COUNT(*) FROM advances a JOIN employees e ON e.id = a.employee_id "
						+ "WHERE e.company_id = ? AND a.status = 'pending'", companyId)));
	}

	private long count(String sql, long companyId) {
		Long value = this.jdbc.queryForObject(sql, Long.class, companyId);
		return value == null ? 0L : value;
	}

	@Override
	public boolean reject(long companyId, String reason) {
		return this.companyRepository.reject(companyId, reason) == 1;
	}

	@Override
	public boolean updateStatus(long companyId, String status) {
		return this.companyRepository.updateStatus(companyId, status) == 1;
	}

}
