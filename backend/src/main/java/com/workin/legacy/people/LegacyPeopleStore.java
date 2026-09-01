package com.workin.legacy.people;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.workin.legacy.LegacyJdbcValues;

/**
 * The three tables Wave 13.4c touches: {@code employee_docs},
 * {@code complaints}, and the join-request view over {@code employees}.
 *
 * <h2>Two of the three are not tenant-scoped by their own column</h2>
 * <p>{@code employee_docs} has <b>no</b> {@code company_id}: it is reached only
 * through an employee whose company the caller has already verified, so every
 * query here takes an employee id the service has checked first. Losing that
 * check would make the table globally readable, which is why the ordering is
 * enforced in the service rather than assumed here.
 *
 * <p>{@code complaints} has a <em>nullable</em> {@code company_id}, because
 * {@code create.php} accepts anonymous submissions. Rows with a null company
 * are unreachable through {@code list.php}'s {@code company_id = ?} filter --
 * see {@link LegacyComplaintService}.
 */
@Repository
public class LegacyPeopleStore {

	/** {@code sql_employee_display_name('e')}. */
	private static final String DISPLAY_NAME =
			"TRIM(CONCAT(COALESCE(e.first_name,''),' ',COALESCE(e.last_name,'')))";

	private final JdbcTemplate jdbcTemplate;

	public LegacyPeopleStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	private long count(String sql, Object... binds) {
		Long total = jdbcTemplate.queryForObject(sql, Long.class, binds);
		return total == null ? 0L : total;
	}

	// ---------------- employee_docs ----------------

	public boolean employeeInCompany(long employeeId, long companyId) {
		return count("SELECT COUNT(*) FROM employees WHERE id=? AND company_id=?",
				employeeId, companyId) > 0;
	}

	public long countDocs(List<String> predicates, List<Object> binds) {
		return count("SELECT COUNT(*) FROM employee_docs WHERE " + String.join(" AND ", predicates),
				binds.toArray());
	}

	/** Four columns only -- the list does not return the row's every column. */
	public List<Map<String, Object>> docs(
			List<String> predicates, List<Object> binds, long limit, long offset) {
		List<Object> args = new ArrayList<>(binds);
		args.add(limit);
		args.add(offset);
		return jdbcTemplate.query(
				"SELECT id, doc_type, file_url, uploaded_at FROM employee_docs WHERE "
						+ String.join(" AND ", predicates) + " ORDER BY uploaded_at DESC LIMIT ? OFFSET ?",
				LegacyJdbcValues.rowMapper(), args.toArray());
	}

	/**
	 * A document joined to its owning employee's company, which is the only way
	 * to tenant-check a table that has no company of its own.
	 */
	public Map<String, Object> docWithOwner(long companyId, long docId) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT d.*, e.company_id AS owner_company_id FROM employee_docs d"
						+ " JOIN employees e ON e.id = d.employee_id"
						+ " WHERE d.id = ? AND e.company_id = ?",
				LegacyJdbcValues.rowMapper(), docId, companyId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	public long insertDoc(long employeeId, String docType, String fileUrl) {
		return com.workin.legacy.LegacyGeneratedKeys.insert(jdbcTemplate,
				"INSERT INTO employee_docs (employee_id, doc_type, file_url) VALUES (?, ?, ?)",
				employeeId, docType, fileUrl);
	}

	public Map<String, Object> docById(long id) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT * FROM employee_docs WHERE id=?", LegacyJdbcValues.rowMapper(), id);
		return rows.isEmpty() ? null : rows.get(0);
	}

	public void updateDoc(long id, String docType) {
		jdbcTemplate.update("UPDATE employee_docs SET doc_type=? WHERE id=?", docType, id);
	}

	public void deleteDoc(long id) {
		jdbcTemplate.update("DELETE FROM employee_docs WHERE id=?", id);
	}

	// ---------------- complaints ----------------

	public long insertComplaint(
			Long employeeId, Long companyId, String source, String name, String email,
			String phone, String message) {
		return com.workin.legacy.LegacyGeneratedKeys.insert(jdbcTemplate,
				"INSERT INTO complaints (employee_id, company_id, source, name, email, phone, message)"
						+ " VALUES (?, ?, ?, ?, ?, ?, ?)",
				employeeId, companyId, source, name, email, phone, message);
	}

	/** The count joins {@code employees} too, because the search matches its display name. */
	public long countComplaints(List<String> predicates, List<Object> binds) {
		return count("SELECT COUNT(*) FROM complaints AS c"
				+ " LEFT JOIN employees AS e ON e.id = c.employee_id"
				+ " WHERE " + String.join(" AND ", predicates),
				binds.toArray());
	}

	public List<Map<String, Object>> complaints(
			List<String> predicates, List<Object> binds, long limit, long offset) {
		List<Object> args = new ArrayList<>(binds);
		args.add(limit);
		args.add(offset);
		return jdbcTemplate.query(
				// employee_code is selected by list.php and by no other complaints
				// endpoint -- update.php re-reads the same join without it, so
				// complaintWithEmployee() below must stay as it is.
				"SELECT c.*, " + DISPLAY_NAME + " AS employee_name, e.photo_url AS photo_url,"
						+ " e.employee_code AS employee_code"
						+ " FROM complaints AS c LEFT JOIN employees AS e ON e.id = c.employee_id"
						+ " WHERE " + String.join(" AND ", predicates)
						+ " ORDER BY c.created_at DESC, c.id DESC LIMIT ? OFFSET ?",
				LegacyJdbcValues.rowMapper(), args.toArray());
	}

	/** The ownership probe {@code update.php} and {@code delete.php} share. */
	public boolean complaintOwnedBy(long id, long companyId, String source) {
		return count("SELECT COUNT(*) FROM complaints WHERE id=? AND company_id=? AND source=?",
				id, companyId, source) > 0;
	}

	public void updateComplaint(long id, List<String> assignments, List<Object> values) {
		List<Object> args = new ArrayList<>(values);
		args.add(id);
		jdbcTemplate.update(
				"UPDATE complaints SET " + String.join(", ", assignments) + " WHERE id=?",
				args.toArray());
	}

	/** The post-update re-read is by id alone, with no company or source filter. */
	public Map<String, Object> complaintWithEmployee(long id) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT c.*, " + DISPLAY_NAME + " AS employee_name, e.photo_url AS photo_url"
						+ " FROM complaints AS c LEFT JOIN employees AS e ON e.id = c.employee_id"
						+ " WHERE c.id=?",
				LegacyJdbcValues.rowMapper(), id);
		return rows.isEmpty() ? null : rows.get(0);
	}

	public void deleteComplaint(long id) {
		jdbcTemplate.update("DELETE FROM complaints WHERE id=?", id);
	}

	// ---------------- company_join_requests ----------------

	public long countJoinRequests(List<String> predicates, List<Object> binds) {
		return count("SELECT COUNT(*) FROM employees AS e WHERE " + String.join(" AND ", predicates),
				binds.toArray());
	}

	/** Five columns, not the whole employee row. */
	public List<Map<String, Object>> joinRequests(
			List<String> predicates, List<Object> binds, long limit, long offset) {
		List<Object> args = new ArrayList<>(binds);
		args.add(limit);
		args.add(offset);
		return jdbcTemplate.query(
				"SELECT e.id, " + DISPLAY_NAME + " AS name, e.phone, e.created_at,"
						+ " e.join_request_status"
						+ " FROM employees AS e WHERE " + String.join(" AND ", predicates)
						+ " ORDER BY e.created_at DESC LIMIT ? OFFSET ?",
				LegacyJdbcValues.rowMapper(), args.toArray());
	}

	public Map<String, Object> employeeRow(long id, long companyId, String role) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT * FROM employees WHERE id=? AND company_id=? AND role=?",
				LegacyJdbcValues.rowMapper(), id, companyId, role);
		return rows.isEmpty() ? null : rows.get(0);
	}

	public Map<String, Object> employeeById(long id) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT * FROM employees WHERE id=?", LegacyJdbcValues.rowMapper(), id);
		return rows.isEmpty() ? null : rows.get(0);
	}

	public void acceptJoinRequest(long id) {
		jdbcTemplate.update(
				"UPDATE employees SET join_request_status='accepted', is_active=1 WHERE id=?", id);
	}

	/** Rejection <b>deletes</b> the provisional row so the phone can be reused. */
	public void deleteEmployee(long id, long companyId) {
		jdbcTemplate.update("DELETE FROM employees WHERE id=? AND company_id=?", id, companyId);
	}
}
