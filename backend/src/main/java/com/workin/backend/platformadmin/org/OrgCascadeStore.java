package com.workin.backend.platformadmin.org;

import java.util.ArrayList;
import java.util.Arrays;
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
			departmentsByBranch = departmentsByBranch
					+ " UNION SELECT d.id, d.name, b.id AS grp FROM departments d"
					+ " INNER JOIN branches b ON b.id = ? AND b.company_id = d.company_id"
					+ " WHERE d.id = ?" + inCompany.formatted("d");
			departmentsByBranchArgs = scoped
					? new Object[] { kept.departmentId(), companyId, kept.branchId(), kept.departmentId(), companyId }
					: new Object[] { kept.departmentId(), kept.branchId(), kept.departmentId() };
		}

		List<Part> parts = List.of(
				new Part("SELECT b.id, b.name, b.company_id AS grp FROM branches b"
						+ " WHERE (b.is_active = 1 OR b.id = ?)" + inCompany.formatted("b"),
						args(scoped, companyId, kept.branchId())),
				new Part("SELECT d.id, d.name, d.company_id AS grp FROM departments d"
						+ " WHERE (d.is_active = 1 OR d.id = ?)" + inCompany.formatted("d"),
						args(scoped, companyId, kept.departmentId())),
				new Part(departmentsByBranch, departmentsByBranchArgs),
				new Part("SELECT jt.id, jt.name, jt.department_id AS grp FROM job_titles jt"
						+ " INNER JOIN departments d ON d.id = jt.department_id AND d.company_id = jt.company_id"
						+ " WHERE (jt.is_active = 1 OR jt.id = ?)" + inCompany.formatted("jt"),
						args(scoped, companyId, kept.jobTitleId())),
				new Part("SELECT jt.id, jt.name, jt.company_id AS grp FROM job_titles jt"
						+ " WHERE (jt.is_active = 1 OR jt.id = ?)" + inCompany.formatted("jt"),
						args(scoped, companyId, kept.jobTitleId())));

		List<Map<Long, List<OrgCascade.Option>>> maps = groupedInOneTrip(parts);
		return new OrgCascade(maps.get(0), maps.get(1), maps.get(2), maps.get(3), maps.get(4));
	}

	/** One of the cascade's lists: the select that produces it, and its arguments. */
	private record Part(String sql, Object[] args) {
	}

	/**
	 * The five lists in one round trip.
	 *
	 * <p>They were five queries, which is five network round trips on every page
	 * that opens a form and on every unscoped list page. Against a database in
	 * another network -- 106 ms away, measured -- that was half a second of a
	 * page's wait for data that one statement returns. They are unioned with a
	 * discriminator and split here.
	 *
	 * <p>Each part keeps its own order because the single {@code ORDER BY} sorts
	 * by the discriminator first and then by the same {@code grp, name} every
	 * part used: the rows arrive grouped by part, in each part's own order, and
	 * {@link LinkedHashMap} keeps the groups in the order they first appear, as
	 * five separate queries did.
	 *
	 * <p>{@code id} breaks the tie that {@code name} alone leaves. The collation
	 * is {@code utf8mb4_unicode_ci}, so {@code Tie} and {@code tie} sort equal
	 * and the order between them was whatever each plan happened to produce --
	 * one plan per query before, one for the union now, and they do not agree.
	 * The rows are the same either way; this makes the order the same too.
	 */
	private List<Map<Long, List<OrgCascade.Option>>> groupedInOneTrip(List<Part> parts) {
		StringBuilder sql = new StringBuilder();
		List<Object> args = new ArrayList<>();
		for (int part = 0; part < parts.size(); part++) {
			sql.append(part == 0 ? "" : " UNION ALL ")
					.append("SELECT ").append(part).append(" AS part, id, name, grp FROM (")
					.append(parts.get(part).sql()).append(") AS part").append(part);
			args.addAll(Arrays.asList(parts.get(part).args()));
		}
		sql.append(" ORDER BY part, grp, name, id");

		List<Map<Long, List<OrgCascade.Option>>> maps = new ArrayList<>();
		for (int part = 0; part < parts.size(); part++) {
			maps.add(new LinkedHashMap<>());
		}
		this.jdbcTemplate.query(sql.toString(),
				(RowCallbackHandler) rs -> maps.get(rs.getInt("part"))
						.computeIfAbsent(rs.getLong("grp"), key -> new ArrayList<>())
						.add(new OrgCascade.Option(rs.getLong("id"), rs.getString("name"))),
				args.toArray());
		return maps;
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
