package com.workin.backend.platformadmin.content;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

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
@Profile("phase1-mysql")
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
