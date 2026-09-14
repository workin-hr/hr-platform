package com.workin.backend.platformadmin.org;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The companies an administrator's form can name: legacy's
 * {@code org_active_companies()}, which lists {@code status = 'active'} by name.
 */
@Repository
public class ActiveCompanies {

	private final JdbcTemplate jdbcTemplate;

	public ActiveCompanies(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<CompanyOption> all() {
		return this.jdbcTemplate.query(
				"SELECT id, company_name FROM companies WHERE status = 'active' ORDER BY company_name, id",
				(rs, rowNum) -> new CompanyOption(rs.getLong("id"), rs.getString("company_name")));
	}

	public record CompanyOption(long id, String name) {
	}
}
