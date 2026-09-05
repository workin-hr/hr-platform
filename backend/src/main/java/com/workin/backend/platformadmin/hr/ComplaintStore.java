package com.workin.backend.platformadmin.hr;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.workin.backend.platformadmin.web.DashboardListFilters;
import com.workin.backend.platformadmin.web.DashboardPage;
import com.workin.backend.platformadmin.web.DashboardSession;

/** {@code complaints_paginate()} and the writes {@code complaints.php} makes. */
@Repository
@Profile("phase1-mysql")
public class ComplaintStore {

	private final JdbcTemplate jdbcTemplate;

	public ComplaintStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	private static final RowMapper<Complaint> MAPPER = (rs, rowNum) -> {
		long employeeId = rs.getLong("employee_id");
		boolean noEmployee = rs.wasNull();
		long companyId = rs.getLong("company_id");
		boolean noCompany = rs.wasNull();
		return new Complaint(
				rs.getLong("id"),
				noEmployee ? null : employeeId,
				noCompany ? null : companyId,
				rs.getString("company_name"),
				rs.getString("source"),
				rs.getString("name"),
				rs.getString("email"),
				rs.getString("phone"),
				rs.getString("message"),
				rs.getString("status"),
				rs.getString("reply"),
				rs.getString("created_at"));
	};

	/**
	 * The list, which is two different queries depending on who is asking.
	 *
	 * <p>A <b>company owner</b> sees its own company's {@code employee}
	 * complaints and nothing else -- not even the {@code company_support} ones
	 * it raised itself, which are the platform's to answer. Everyone else gets
	 * the filtered view, where {@code source} is a filter rather than a fixed
	 * condition.
	 *
	 * <p>That split is legacy's {@code $isComp = isCompany()}, and it is
	 * written out per audience here rather than collapsed, because an HR
	 * session falls on the <em>second</em> branch in PHP -- it sees its
	 * company's support complaints where the owner does not. Surprising, and
	 * not this port's to change.
	 */
	public DashboardPage<Complaint> paginate(
			DashboardSession session, DashboardListFilters filters, String source, String status,
			String dateFrom, String dateTo) {
		List<Object> params = new ArrayList<>();
		StringBuilder where = new StringBuilder("1=1");

		if (session.isCompany()) {
			where.append(" AND c.company_id = ? AND c.source = 'employee'");
			params.add(session.companyId());
		} else {
			if (filters.companyId() > 0) {
				where.append(" AND c.company_id = ?");
				params.add(filters.companyId());
			}
			if ("employee".equals(source) || "company_support".equals(source)) {
				where.append(" AND c.source = ?");
				params.add(source);
			}
		}
		if (Complaint.isValidStatus(status)) {
			where.append(" AND c.status = ?");
			params.add(status);
		}
		if (!filters.search().isEmpty()) {
			where.append(" AND (c.name LIKE ? OR c.phone LIKE ? OR c.email LIKE ?"
					+ " OR c.message LIKE ? OR co.company_name LIKE ?)");
			String like = "%" + filters.search() + "%";
			for (int i = 0; i < 5; i++) {
				params.add(like);
			}
		}
		// DATE(created_at), so a complaint filed at 23:00 on the closing day is
		// still inside the range.
		if (dateFrom != null && !dateFrom.isBlank()) {
			where.append(" AND DATE(c.created_at) >= ?");
			params.add(dateFrom.trim());
		}
		if (dateTo != null && !dateTo.isBlank()) {
			where.append(" AND DATE(c.created_at) <= ?");
			params.add(dateTo.trim());
		}

		// The company join is a LEFT JOIN and is in the count too, because the
		// search reaches company_name through it.
		String from = " FROM complaints c LEFT JOIN companies co ON co.id = c.company_id";

		Integer total = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*)" + from + " WHERE " + where, Integer.class, params.toArray());

		List<Object> pageParams = new ArrayList<>(params);
		pageParams.add(filters.perPage());
		pageParams.add(DashboardPage.offsetFor(filters.page(), filters.perPage()));

		List<Complaint> rows = this.jdbcTemplate.query(
				"SELECT c.*, co.company_name" + from + " WHERE " + where
						+ " ORDER BY c.created_at DESC, c.id DESC LIMIT ? OFFSET ?",
				MAPPER, pageParams.toArray());

		return DashboardPage.of(rows, total == null ? 0 : total, filters.page(), filters.perPage());
	}

	/**
	 * The company a complaint belongs to.
	 *
	 * <p>Nullable on this table alone: a {@code company_support} complaint from
	 * someone not yet attached to a company has neither id. The caller must
	 * decide what an unattached complaint means rather than assuming a company.
	 */
	public Long companyOf(long id) {
		if (id <= 0) {
			return null;
		}
		List<Long> found = this.jdbcTemplate.queryForList(
				"SELECT company_id FROM complaints WHERE id = ?", Long.class, id);
		return found.isEmpty() ? null : found.get(0);
	}

	public Complaint find(long id) {
		List<Complaint> rows = this.jdbcTemplate.query(
				"SELECT c.*, co.company_name FROM complaints c"
						+ " LEFT JOIN companies co ON co.id = c.company_id WHERE c.id = ?",
				MAPPER, id);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** Whether the row exists at all, told apart from a null {@code company_id}. */
	public boolean exists(long id) {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM complaints WHERE id = ?", Integer.class, id);
		return count != null && count > 0;
	}

	public int reply(long id, String reply, String status) {
		return this.jdbcTemplate.update(
				"UPDATE complaints SET reply = ?, status = ? WHERE id = ?", reply, status, id);
	}

	public int setStatus(long id, String status) {
		return this.jdbcTemplate.update(
				"UPDATE complaints SET status = ? WHERE id = ?", status, id);
	}

	public int delete(long id) {
		return this.jdbcTemplate.update("DELETE FROM complaints WHERE id = ?", id);
	}

}
