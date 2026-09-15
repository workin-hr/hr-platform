package com.workin.backend.platformadmin.org;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/**
 * The maps legacy's form scripts fill a form's selects from, as
 * {@code org_filter_cascade_payload()} builds them ({@code org_helper.php:246-264}
 * and {@code :337-449}), for the companies one form may use.
 *
 * <p><b>R-051</b>: legacy builds them from queries whose only predicate is
 * {@code is_active = 1} and lets the browser pick the chosen company's group.
 * Measured against the production copy that is 3,671 rows across 283
 * companies handed to any company-scoped session that opens the page.
 * Filtering client-side is not filtering; the predicate belongs here. A
 * {@code companyId} of zero is the administrator with no filter, whose reach
 * genuinely is every company, and is the only case that returns every
 * company's rows.
 *
 * <p>A department is listed under a branch, and a job title under a department,
 * only when both belong to the same company. {@code department_branches} carries
 * no company, a job title's {@code department_id} is not held to its own company,
 * and legacy checks neither; a department or job title listed across companies is
 * one the service refuses on save.
 */
@Repository
public class OrgCascadeStore {

	private final JdbcTemplate jdbcTemplate;

	public OrgCascadeStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** Active rows only, as legacy lists them. */
	public OrgCascade cascade(long companyId) {
		return cascade(companyId, OrgCascade.Kept.NONE);
	}

	/**
	 * Active rows, and the rows {@code kept} names even once retired. The kept
	 * department is also listed under the kept branch, whether or not the two are
	 * linked: {@code employee-form.js} disables a department select its branch lists
	 * nothing for, and a disabled select posts nothing, so an unchanged save of an
	 * employee whose department is not linked to their branch would clear it (D-250).
	 * {@code kept} is for an edit, whose {@code companyId} is the row's own.
	 */
	public OrgCascade cascade(long companyId, OrgCascade.Kept kept) {
		boolean scoped = companyId > 0;
		String inCompany = scoped ? " AND %s.company_id = ?" : "";
		String departmentsByBranch = "SELECT DISTINCT d.id, d.name, db.branch_id AS grp FROM departments d"
				+ " INNER JOIN department_branches db ON db.department_id = d.id"
				+ " INNER JOIN branches b ON b.id = db.branch_id AND b.company_id = d.company_id"
				+ " WHERE (d.is_active = 1 OR d.id = ?)" + inCompany.formatted("d");
		Object[] departmentsByBranchArgs = args(scoped, companyId, kept.departmentId());
		if (kept.departmentId() > 0 && kept.branchId() > 0) {
			departmentsByBranch = "SELECT id, name, grp FROM (" + departmentsByBranch
					+ " UNION SELECT d.id, d.name, b.id AS grp FROM departments d"
					+ " INNER JOIN branches b ON b.id = ? AND b.company_id = d.company_id"
					+ " WHERE d.id = ?" + inCompany.formatted("d") + ") placed ORDER BY grp, name";
			departmentsByBranchArgs = scoped
					? new Object[] { kept.departmentId(), companyId, kept.branchId(), kept.departmentId(), companyId }
					: new Object[] { kept.departmentId(), kept.branchId(), kept.departmentId() };
		}
		else {
			departmentsByBranch += " ORDER BY db.branch_id, d.name";
		}
		return new OrgCascade(
				grouped("SELECT b.id, b.name, b.company_id AS grp FROM branches b"
						+ " WHERE (b.is_active = 1 OR b.id = ?)" + inCompany.formatted("b")
						+ " ORDER BY b.company_id, b.name", args(scoped, companyId, kept.branchId())),
				grouped("SELECT d.id, d.name, d.company_id AS grp FROM departments d"
						+ " WHERE (d.is_active = 1 OR d.id = ?)" + inCompany.formatted("d")
						+ " ORDER BY d.company_id, d.name", args(scoped, companyId, kept.departmentId())),
				grouped(departmentsByBranch, departmentsByBranchArgs),
				grouped("SELECT jt.id, jt.name, jt.department_id AS grp FROM job_titles jt"
						+ " INNER JOIN departments d ON d.id = jt.department_id AND d.company_id = jt.company_id"
						+ " WHERE (jt.is_active = 1 OR jt.id = ?)" + inCompany.formatted("jt")
						+ " ORDER BY jt.department_id, jt.name", args(scoped, companyId, kept.jobTitleId())),
				grouped("SELECT jt.id, jt.name, jt.company_id AS grp FROM job_titles jt"
						+ " WHERE (jt.is_active = 1 OR jt.id = ?)" + inCompany.formatted("jt")
						+ " ORDER BY jt.company_id, jt.name", args(scoped, companyId, kept.jobTitleId())));
	}

	/**
	 * Active shifts by company, for the employee form's shift select. Not part of
	 * legacy's payload: its form lists one company's shifts from the server, which
	 * leaves a form that names its company with none (D-250).
	 */
	public Map<Long, List<OrgCascade.Option>> shiftsByCompany(long companyId) {
		boolean scoped = companyId > 0;
		return grouped("SELECT s.id, s.name, s.company_id AS grp FROM shifts s"
				+ " WHERE s.is_active = 1" + (scoped ? " AND s.company_id = ?" : "")
				+ " ORDER BY s.company_id, s.name", scoped ? new Object[] { companyId } : new Object[0]);
	}

	private static Object[] args(boolean scoped, long companyId, long keptId) {
		return scoped ? new Object[] { keptId, companyId } : new Object[] { keptId };
	}

	private Map<Long, List<OrgCascade.Option>> grouped(String sql, Object[] args) {
		Map<Long, List<OrgCascade.Option>> grouped = new LinkedHashMap<>();
		this.jdbcTemplate.query(sql,
				(RowCallbackHandler) rs -> grouped
						.computeIfAbsent(rs.getLong("grp"), key -> new ArrayList<>())
						.add(new OrgCascade.Option(rs.getLong("id"), rs.getString("name"))),
				args);
		return grouped;
	}
}
