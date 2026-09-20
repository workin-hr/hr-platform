package com.workin.backend.platformadmin;

import java.util.List;

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

	/**
	 * {@code dashboard/pages/companies/detail.php:11-27}, query for query, each scoped to the one
	 * company. Two changes: the employees table reads its fifteen rows with {@code LIMIT} rather
	 * than every row, its count being the total already counted; and each ordering ends on the id,
	 * so rows that tie on legacy's keys come in one order rather than whichever the server returns.
	 */
	@Override
	public java.util.Optional<CompanyDetail> detail(long companyId) {
		record Company(CompanyView view, CompanyDetail.Profile profile, String rejectionReason) {
		}
		java.util.Optional<Company> found = this.jdbc.query("""
				SELECT id, company_name, status, phone, email, otp_verified, created_at,
					logo_url, commercial_reg_url, rejection_reason
				FROM companies WHERE id = ?""",
				(rs, row) -> new Company(
						new CompanyView(rs.getLong("id"), rs.getString("company_name"), rs.getString("status")),
						new CompanyDetail.Profile(rs.getString("phone"), rs.getString("email"),
								rs.getInt("otp_verified") != 0, rs.getString("created_at"),
								rs.getString("logo_url"), rs.getString("commercial_reg_url")),
						rs.getString("rejection_reason")),
				companyId).stream().findFirst();
		return found.map(company -> new CompanyDetail(company.view(), company.profile(),
				company.rejectionReason(),
				count("SELECT COUNT(*) FROM employees WHERE company_id = ? AND is_active = 1", companyId),
				count("SELECT COUNT(*) FROM employees WHERE company_id = ?", companyId),
				count("SELECT COUNT(DISTINCT a.employee_id) FROM attendance a"
						+ " JOIN employees e ON e.id = a.employee_id"
						+ " WHERE e.company_id = ? AND DATE(a.check_in) = CURDATE()", companyId),
				// Legacy's requests and advances carry no company_id, so the
				// scope comes through employees -- the same join detail.php uses.
				count("SELECT COUNT(*) FROM requests r JOIN employees e ON e.id = r.employee_id "
						+ "WHERE e.company_id = ? AND r.status = 'pending'", companyId),
				count("SELECT COUNT(*) FROM advances a JOIN employees e ON e.id = a.employee_id "
						+ "WHERE e.company_id = ? AND a.status = 'pending'", companyId),
				this.jdbc.query("SELECT b.name, (SELECT COUNT(*) FROM employees"
						+ " WHERE branch_id = b.id AND is_active = 1) AS active_employees"
						+ " FROM branches b WHERE b.company_id = ? ORDER BY b.name, b.id",
						(rs, row) -> new CompanyDetail.Branch(rs.getString("name"), rs.getLong("active_employees")),
						companyId),
				// role is an enum, so ORDER BY reads its declared order:
				// company_admin, hr, manager.
				this.jdbc.query("SELECT phone, role FROM employees WHERE company_id = ?"
						+ " AND role IN ('hr', 'manager', 'company_admin') ORDER BY role, id",
						(rs, row) -> new CompanyDetail.StaffUser(rs.getString("phone"), rs.getString("role")),
						companyId),
				this.jdbc.query("""
						SELECT e.id,
							COALESCE(NULLIF(TRIM(e.employee_code), ''), CAST(e.id AS CHAR)) AS emp_code,
							TRIM(CONCAT(COALESCE(e.first_name, ''), ' ', COALESCE(e.last_name, ''))) AS employee_name,
							e.is_active, e.hire_date, e.phone, b.name AS branch_name
						FROM employees e LEFT JOIN branches b ON b.id = e.branch_id
						WHERE e.company_id = ?
						ORDER BY e.is_active DESC, e.first_name, e.last_name, e.id
						LIMIT ?""",
						(rs, row) -> new CompanyDetail.Employee(rs.getLong("id"), rs.getString("emp_code"),
								rs.getString("employee_name"), rs.getString("phone"), rs.getString("branch_name"),
								rs.getString("hire_date"), rs.getInt("is_active") != 0),
						companyId, CompanyDetail.EMPLOYEES_LISTED)));
	}

	@Override
	public java.util.Optional<DeletionTarget> deletionTarget(long companyId) {
		return this.jdbc.query("SELECT id, company_name, phone, status FROM companies WHERE id = ?",
				(rs, rowNum) -> new DeletionTarget(
						new CompanyView(rs.getLong("id"), rs.getString("company_name"), rs.getString("status")),
						rs.getString("phone")),
				companyId).stream().findFirst();
	}

	@Override
	public List<String> dialCodes() {
		return this.jdbc.queryForList(
				"SELECT country_code FROM phone_countries WHERE is_active = 1 "
						+ "ORDER BY sort_order, id", String.class);
	}

	@Override
	public boolean phoneTaken(String phone, long excludeCompanyId) {
		Long found = this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM companies WHERE phone = ? AND id <> ?",
				Long.class, phone, excludeCompanyId);
		return found != null && found > 0;
	}

	@Override
	public boolean companyCodeTaken(String companyCode, long excludeCompanyId) {
		// UPPER() on the column, as the PHP does: the code is compared without
		// regard to how it was typed.
		Long found = this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM companies WHERE UPPER(company_code) = ? AND id <> ?",
				Long.class, companyCode, excludeCompanyId);
		return found != null && found > 0;
	}

	@Override
	public boolean lookupsExist(long activityId, long titleId, long sizeId) {
		return rowExists("company_activities", activityId)
				&& rowExists("company_titles", titleId)
				&& rowExists("company_sizes", sizeId);
	}

	private boolean rowExists(String table, long id) {
		Long found = this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM " + table + " WHERE id = ?", Long.class, id);
		return found != null && found > 0;
	}

	@Override
	public java.util.Optional<EditableCompany> editable(long companyId) {
		return this.jdbc.query("""
				SELECT id, company_name, first_name, last_name, country_code, phone,
					main_branch_address, company_activity_id, company_title_id,
					company_size_id, company_code
				FROM companies WHERE id = ?""",
				(rs, row) -> new EditableCompany(rs.getLong("id"),
						rs.getString("company_name"), rs.getString("first_name"),
						rs.getString("last_name"), rs.getString("country_code"),
						rs.getString("phone"), rs.getString("main_branch_address"),
						rs.getLong("company_activity_id"), rs.getLong("company_title_id"),
						rs.getLong("company_size_id"), rs.getString("company_code")),
				companyId).stream().findFirst();
	}

	@Override
	public java.util.Optional<StoredFiles> storedFiles(long companyId) {
		return this.jdbc.query(
				"SELECT logo_url, commercial_reg_url FROM companies WHERE id = ?",
				(rs, row) -> new StoredFiles(rs.getString("logo_url"),
						rs.getString("commercial_reg_url")),
				companyId).stream().findFirst();
	}

	/**
	 * {@code company_admin_create()}. The three flags are the PHP's literals:
	 * a company the administrator creates is active, has no OTP to verify and
	 * needs no profile step -- it was filled in on this form.
	 */
	@Override
	public long create(CompanyForm.CompanyWrite write, String passwordHash,
			String logoUrl, String commercialRegUrl) {
		long companyId = com.workin.legacy.LegacyGeneratedKeys.insert(this.jdbc, """
				INSERT INTO companies
					(company_name, first_name, last_name, country_code, phone, password_hash,
					 main_branch_address, company_activity_id, company_title_id, company_size_id,
					 logo_url, commercial_reg_url, status, otp_verified, profile_completed)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', 1, 1)""",
				write.companyName(), write.firstName(), write.lastName(),
				write.countryCode(), write.phone(), passwordHash,
				write.mainBranchAddress(), write.activityId(), write.titleId(),
				write.sizeId(), logoUrl, commercialRegUrl);

		// The main branch, which PHP inserts in the same breath: a company with
		// no branch has nowhere to record attendance.
		this.jdbc.update(
				"INSERT INTO branches (company_id, name, address, is_active) VALUES (?, ?, ?, 1)",
				companyId, write.companyName(), write.mainBranchAddress());
		return companyId;
	}

	@Override
	public void update(long companyId, CompanyForm.CompanyWrite write, String passwordHash,
			String logoUrl, String commercialRegUrl) {
		List<Object> binds = new java.util.ArrayList<>(List.of(
				write.companyName(), write.firstName(), write.lastName(),
				write.countryCode(), write.phone(), write.mainBranchAddress(),
				write.activityId(), write.titleId(), write.sizeId()));
		StringBuilder sql = new StringBuilder("""
				UPDATE companies SET company_name = ?, first_name = ?, last_name = ?,
					country_code = ?, phone = ?, main_branch_address = ?,
					company_activity_id = ?, company_title_id = ?, company_size_id = ?""");
		// logo_url and commercial_reg_url are always written, because the
		// caller has already resolved "no new upload" to the stored value.
		sql.append(", logo_url = ?, commercial_reg_url = ?");
		binds.add(logoUrl);
		binds.add(commercialRegUrl);
		if (write.companyCode() != null) {
			sql.append(", company_code = ?");
			binds.add(write.companyCode());
		}
		if (passwordHash != null) {
			sql.append(", password_hash = ?");
			binds.add(passwordHash);
		}
		sql.append(" WHERE id = ?");
		binds.add(companyId);
		this.jdbc.update(sql.toString(), binds.toArray());

		// The main branch follows the company's name and address. PHP takes the
		// lowest id as the main one, and inserts one where the company has none.
		List<Long> mainBranch = this.jdbc.queryForList(
				"SELECT id FROM branches WHERE company_id = ? ORDER BY id ASC LIMIT 1",
				Long.class, companyId);
		if (mainBranch.isEmpty()) {
			this.jdbc.update(
					"INSERT INTO branches (company_id, name, address, is_active) VALUES (?, ?, ?, 1)",
					companyId, write.companyName(), write.mainBranchAddress());
		}
		else {
			this.jdbc.update("UPDATE branches SET name = ?, address = ? WHERE id = ?",
					write.companyName(), write.mainBranchAddress(), mainBranch.get(0));
		}
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
