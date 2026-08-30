package com.workin.legacy.notifications;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.workin.legacy.LegacyJdbcValues;
import com.workin.legacy.LegacyPagination;

/**
 * The read and write path behind {@code apis/api/notifications/*.php}.
 *
 * <p>Every method takes a {@link LegacyNotificationInbox} and splices its
 * fragment into the {@code WHERE} clause, which is the module's only tenant
 * scope. The two exceptions are deliberate and are PHP's: the {@code UPDATE}
 * in {@code one.php} and {@code mark_read.php}'s single-id branch, and the
 * {@code DELETE} in {@code delete.php}'s single-id branch, all address the row
 * by bare {@code id}. That is safe only because each is preceded by an
 * inbox-scoped ownership query in the same request, so the port keeps those
 * two statements together with their guard rather than "improving" the SQL.
 */
@Repository
public class LegacyNotificationStore {

	/** {@code sql_employee_display_name('fe')} ({@code functions.php:169-176}). */
	private static final String FROM_NAME =
			"TRIM(CONCAT(COALESCE(fe.first_name,''),' ',COALESCE(fe.last_name,'')))";

	private final JdbcTemplate jdbcTemplate;

	public LegacyNotificationStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/** {@code list.php}'s and {@code unread_count.php}'s {@code COUNT(*)}. */
	public long count(LegacyNotificationInbox inbox, boolean unreadOnly) {
		String sql = "SELECT COUNT(*) FROM notifications AS n WHERE " + inbox.sql()
				+ (unreadOnly ? " AND n.is_read = 0" : "");
		Long total = jdbcTemplate.queryForObject(sql, Long.class, inbox.params().toArray());
		return total == null ? 0L : total;
	}

	/**
	 * {@code list.php}: {@code n.*} plus the sender's phone and display name
	 * through a LEFT JOIN, so a notification with no sender still lists with
	 * both columns null.
	 */
	public List<Map<String, Object>> list(LegacyNotificationInbox inbox, LegacyPagination.Params pagination) {
		String sql = "SELECT n.*, fe.phone AS from_phone, " + FROM_NAME + " AS from_name "
				+ "FROM notifications AS n "
				+ "LEFT JOIN employees AS fe ON fe.id = n.from_employee_id "
				+ "WHERE " + inbox.sql()
				+ " ORDER BY n.created_at DESC, n.id DESC LIMIT ? OFFSET ?";
		List<Object> binds = new ArrayList<>(inbox.params());
		binds.add(pagination.limit());
		binds.add(pagination.offset());
		return jdbcTemplate.query(sql, rowMapper(), binds.toArray());
	}

	/** {@code one.php}: {@code from_name} but, unlike the list, no {@code from_phone}. */
	public Map<String, Object> byIdInInbox(LegacyNotificationInbox inbox, long id) {
		String sql = "SELECT n.*, " + FROM_NAME + " AS from_name "
				+ "FROM notifications AS n "
				+ "LEFT JOIN employees AS fe ON fe.id = n.from_employee_id "
				+ "WHERE n.id = ? AND " + inbox.sql();
		return single(jdbcTemplate.query(sql, rowMapper(), binds(id, inbox)));
	}

	/**
	 * {@code mark_read.php}'s and {@code delete.php}'s ownership probe --
	 * {@code COUNT(*)}, not a row fetch, and no join.
	 */
	public boolean existsInInbox(LegacyNotificationInbox inbox, long id) {
		Long owned = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM notifications AS n WHERE n.id = ? AND " + inbox.sql(),
				Long.class, binds(id, inbox));
		return owned != null && owned != 0;
	}

	/** The bare-id update shared by {@code one.php} and {@code mark_read.php}. */
	public void markRead(long id) {
		jdbcTemplate.update("UPDATE notifications SET is_read = 1 WHERE id = ?", id);
	}

	/**
	 * {@code mark_read.php}'s all-branch. The {@code AND n.is_read = 0} is
	 * PHP's and is kept: it changes the affected-row count, and while nothing
	 * reads that count today, dropping it would rewrite rows that legacy
	 * leaves untouched.
	 */
	public void markAllRead(LegacyNotificationInbox inbox) {
		jdbcTemplate.update(
				"UPDATE notifications AS n SET n.is_read = 1 WHERE " + inbox.sql() + " AND n.is_read = 0",
				inbox.params().toArray());
	}

	/** The bare-id delete from {@code delete.php}'s single-id branch. */
	public void deleteById(long id) {
		jdbcTemplate.update("DELETE FROM notifications WHERE id = ?", id);
	}

	/** {@code delete.php}'s all-branch: the whole inbox, read or not. */
	public void deleteAll(LegacyNotificationInbox inbox) {
		jdbcTemplate.update(
				"DELETE n FROM notifications AS n WHERE " + inbox.sql(), inbox.params().toArray());
	}

	/** {@code send.php}'s target check: the employee must be in the acting company. */
	public boolean employeeExistsInCompany(long employeeId, long companyId) {
		Long found = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM employees WHERE id = ? AND company_id = ?",
				Long.class, employeeId, companyId);
		return found != null && found != 0;
	}

	/** {@code send.php}'s re-read of the inserted row: {@code SELECT *}, no join. */
	public Map<String, Object> byId(long id) {
		return single(jdbcTemplate.query(
				"SELECT * FROM notifications WHERE id = ?", rowMapper(), id));
	}

	private static Object[] binds(long id, LegacyNotificationInbox inbox) {
		return Stream.concat(Stream.of((Object) id), inbox.params().stream()).toArray();
	}

	private static Map<String, Object> single(List<Map<String, Object>> rows) {
		return rows.isEmpty() ? null : rows.get(0);
	}

	private static RowMapper<Map<String, Object>> rowMapper() {
		return LegacyJdbcValues.rowMapper();
	}
}
