package com.workin.backend.platformadmin.content;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.workin.backend.platformadmin.web.DashboardPage;

/**
 * Writes broadcast rows into {@code notifications}, and reads back what was
 * sent.
 *
 * <p><b>One statement, not a loop.</b> The dashboard selects every
 * recipient and then inserts one row at a time
 * ({@code dashboard_notification_broadcast_all_employees()}), untransacted
 * and unbounded: 2,838 round trips for a single all-employees send against
 * the reference snapshot, and PHP's execution limit cuts it off partway,
 * leaving some employees notified and some not with no way to tell where it
 * stopped. That is **R-045**, recorded and deliberately not reproduced --
 * an {@code INSERT ... SELECT} writes the same rows and cannot half-commit.
 */
@Repository
public class BroadcastStore {

	/**
	 * The dashboard's own value for a platform-wide send, so a row written
	 * here is indistinguishable from one the PHP dashboard wrote.
	 */
	private static final String NOTIFICATION_TYPE = "system_broadcast";

	private final JdbcTemplate jdbcTemplate;

	public BroadcastStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** @return how many employees received it */
	public int broadcastToAllEmployees(String title, String body) {
		return this.jdbcTemplate.update("""
				INSERT INTO notifications
				  (company_id, to_employee_id, recipient_kind, title, body, notification_type, is_read, created_at)
				SELECT e.company_id, e.id, 'employee', ?, ?, ?, 0, NOW()
				  FROM employees e
				 WHERE e.is_active = 1""",
				title, body, NOTIFICATION_TYPE);
	}

	/** @return how many of that company's employees received it */
	public int broadcastToCompanyEmployees(long companyId, String title, String body) {
		return this.jdbcTemplate.update("""
				INSERT INTO notifications
				  (company_id, to_employee_id, recipient_kind, title, body, notification_type, is_read, created_at)
				SELECT e.company_id, e.id, 'employee', ?, ?, ?, 0, NOW()
				  FROM employees e
				 WHERE e.is_active = 1 AND e.company_id = ?""",
				title, body, NOTIFICATION_TYPE, companyId);
	}

	/** How many employees an audience would reach, shown before sending. */
	public int countAllEmployees() {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM employees WHERE is_active = 1", Integer.class);
		return count == null ? 0 : count;
	}

	public boolean companyExists(long companyId) {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM companies WHERE id = ?", Integer.class, companyId);
		return count != null && count > 0;
	}

	/** @param title the notification's title, for the sent-broadcast list */
	public record SentBroadcast(String title, String body, int recipients, String sentAt) {
	}

	private final RowMapper<SentBroadcast> mapper = (rs, rowNum) -> new SentBroadcast(
			rs.getString("title"),
			rs.getString("body"),
			rs.getInt("recipients"),
			rs.getString("sent_at"));

	/**
	 * The broadcasts already sent, grouped: one send produced thousands of
	 * rows, and listing them individually would be useless to an operator
	 * asking what went out.
	 */
	/**
	 * One row of {@code notifications} as the dashboard lists it.
	 *
	 * <p>Per recipient, not per send. {@link SentBroadcast} groups the rows this
	 * surface wrote so an operator can see what went out; this is the table
	 * itself, which is what the dashboard shows and what carries the read flag
	 * and the delete.
	 *
	 * @param recipientKind {@code employee} or {@code company}; a company-inbox
	 *     row has no employee and its recipient reads as the company
	 * @param toName the employee's display name, null for a company-inbox row
	 */
	public record NotificationRow(
			long id, String companyName, String recipientKind, String toName,
			String title, String body, boolean read, String createdAt) {

		/** {@code substr((string) $n['created_at'], 0, 16)} -- to the minute. */
		public String createdAtDisplay() {
			return this.createdAt == null || this.createdAt.isBlank()
					? "—" : this.createdAt.substring(0, Math.min(16, this.createdAt.length()));
		}

		public boolean toCompany() {
			return "company".equals(this.recipientKind);
		}
	}

	private static final RowMapper<NotificationRow> ROW = (rs, index) -> new NotificationRow(
			rs.getLong("id"),
			rs.getString("company_name"),
			rs.getString("recipient_kind"),
			rs.getString("to_name"),
			rs.getString("title"),
			rs.getString("body"),
			rs.getBoolean("is_read"),
			rs.getString("created_at"));

	/**
	 * {@code notifications_paginate()}.
	 *
	 * @param recipientKind {@code employee}, {@code company}, or anything else
	 *     for no filter -- legacy's {@code in_array(..., ['employee','company'])}
	 */
	public DashboardPage<NotificationRow> list(long companyId, String search, String recipientKind,
			String dateFrom, String dateTo, int page, int perPage) {
		StringBuilder where = new StringBuilder("1=1");
		List<Object> params = new ArrayList<>();
		if (companyId > 0) {
			where.append(" AND n.company_id = ?");
			params.add(companyId);
		}
		if ("employee".equals(recipientKind) || "company".equals(recipientKind)) {
			where.append(" AND n.recipient_kind = ?");
			params.add(recipientKind);
		}
		if (search != null && !search.isBlank()) {
			where.append(" AND (n.title LIKE ? OR n.body LIKE ?)");
			params.add("%" + search + "%");
			params.add("%" + search + "%");
		}
		if (dateFrom != null && !dateFrom.isBlank()) {
			where.append(" AND DATE(n.created_at) >= ?");
			params.add(dateFrom);
		}
		if (dateTo != null && !dateTo.isBlank()) {
			where.append(" AND DATE(n.created_at) <= ?");
			params.add(dateTo);
		}

		Integer total = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM notifications n WHERE " + where, Integer.class, params.toArray());

		List<Object> pageParams = new ArrayList<>(params);
		pageParams.add(perPage);
		pageParams.add(DashboardPage.offsetFor(page, perPage));

		List<NotificationRow> rows = this.jdbcTemplate.query(
				"SELECT n.id, n.title, n.body, n.is_read, n.created_at, n.recipient_kind,"
						+ " c.company_name,"
						+ " TRIM(CONCAT(COALESCE(e.first_name,''), ' ', COALESCE(e.last_name,'')))"
						+ " AS to_name"
						+ " FROM notifications n"
						+ " LEFT JOIN companies c ON c.id = n.company_id"
						+ " LEFT JOIN employees e ON e.id = n.to_employee_id"
						+ " WHERE " + where
						+ " ORDER BY n.created_at DESC, n.id DESC"
						+ " LIMIT ? OFFSET ?",
				ROW, pageParams.toArray());

		return DashboardPage.of(rows, total == null ? 0 : total, page, perPage);
	}

	/**
	 * Removes one row by id, as {@code dbDelete('notifications', $id)} does.
	 *
	 * <p>Unscoped, and that is the platform administrator's case rather than an
	 * omission: legacy checks the company only on the {@code isCompany()} branch,
	 * which this surface does not serve.
	 *
	 * @return whether a row existed
	 */
	public boolean delete(long id) {
		return this.jdbcTemplate.update("DELETE FROM notifications WHERE id = ?", id) == 1;
	}

	public List<SentBroadcast> recentBroadcasts(int limit) {
		return this.jdbcTemplate.query("""
				SELECT title, body, COUNT(*) AS recipients, MAX(created_at) AS sent_at
				  FROM notifications
				 WHERE notification_type = ?
				 GROUP BY title, body
				 ORDER BY sent_at DESC
				 LIMIT ?""",
				this.mapper, NOTIFICATION_TYPE, limit);
	}

}
