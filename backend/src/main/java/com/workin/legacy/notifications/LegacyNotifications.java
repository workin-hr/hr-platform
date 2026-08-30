package com.workin.legacy.notifications;

import java.sql.PreparedStatement;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

/**
 * {@code notification_insert()} and its {@code notification_to_employee()}
 * wrapper ({@code helpers/notifications.php:52-143}), for the employee
 * recipient kind.
 *
 * <p>The order is the contract: insert the row, read back its id, then attempt
 * the push with that id in the payload, and swallow anything the delivery
 * throws. PHP does exactly that, which is why a device that never receives the
 * push still leaves a readable notification and a successful API response.
 *
 * <p>The insert is native and carries {@code company_id} from the re-derived
 * tenant, never from a request value -- Hibernate's filters do not apply to
 * native statements.
 */
@Service
public class LegacyNotifications {

	private static final String INSERT = """
			INSERT INTO notifications (
				company_id, recipient_kind, from_employee_id, to_employee_id,
				title, body, notification_type, reference_type, reference_id
			) VALUES (?, 'employee', ?, ?, ?, ?, ?, ?, ?)""";

	/**
	 * The same insert for {@code recipient_kind = 'company'}, where
	 * {@code notification_insert()} forces {@code to_employee_id} to NULL and
	 * skips the push entirely -- there is no device to send it to.
	 */
	private static final String INSERT_COMPANY = """
			INSERT INTO notifications (
				company_id, recipient_kind, from_employee_id, to_employee_id,
				title, body, notification_type, reference_type, reference_id
			) VALUES (?, 'company', ?, NULL, ?, ?, ?, ?, ?)""";

	private final JdbcTemplate jdbcTemplate;
	private final LegacyPushDelivery pushDelivery;

	public LegacyNotifications(DataSource legacyDataSource, LegacyPushDelivery pushDelivery) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
		this.pushDelivery = pushDelivery;
	}

	/**
	 * {@code notification_broadcast_company_employees()}
	 * ({@code helpers/notifications.php:196-221}), which
	 * {@code shifts/update.php} calls when a shift's window is touched.
	 *
	 * <p>Two things it deliberately does not do. It does <b>not</b> exclude the
	 * acting employee: the recipient query is every active employee of the
	 * company, so whoever saved the shift is notified about their own change.
	 * And it is <b>not</b> transactional -- PHP inserts one row per recipient
	 * with no surrounding transaction, so a failure part-way leaves the earlier
	 * notifications committed. Both are reproduced (D-089).
	 *
	 * <p>{@code array_unique(array_filter($ids))} sits between the query and
	 * the loop. Against auto-increment ids neither filter can remove anything --
	 * the query already returns distinct, positive ids -- so it is kept as a
	 * faithful no-op rather than as load-bearing logic.
	 *
	 * @return the number of recipients, as PHP's {@code $sent} counter does
	 */
	public int broadcastToCompanyEmployees(
			long companyId, Long fromEmployeeId, String type, String title, String body,
			String referenceType, Long referenceId) {
		List<Long> recipients = jdbcTemplate.queryForList(
				"SELECT id FROM employees WHERE company_id = ? AND is_active = 1", Long.class, companyId);
		int sent = 0;
		for (Long recipient : new LinkedHashSet<>(recipients)) {
			if (recipient == null || recipient == 0L) {
				continue;
			}
			toEmployee(companyId, recipient, fromEmployeeId, type, title, body, referenceType, referenceId);
			sent++;
		}
		return sent;
	}

	/**
	 * {@code notification_to_company($company_id, $type, $title, $body, $from)}
	 * ({@code helpers/notifications.php:145-166}).
	 *
	 * <p>A company-addressed notification, not a broadcast: <b>one</b> row with
	 * {@code recipient_kind = 'company'} and no recipient employee, rather than
	 * one row per employee. {@code notification_insert()} nulls
	 * {@code to_employee_id} for this kind and its push block is guarded on the
	 * employee kind, so nothing is ever delivered to a device -- which is why
	 * this method has no {@code try/catch} around a delivery call and why
	 * hr-platform#22 does not gate it.
	 *
	 * <p>{@code attendance/import_excel.php} calls this form, after the
	 * transaction has committed. A failure here therefore leaves the imported
	 * attendance rows in place, exactly as PHP does.
	 *
	 * @param fromEmployeeId the acting employee; anything non-positive becomes
	 *        SQL NULL, as {@code notification_normalize_from()} does
	 * @return the inserted notification id
	 */
	public long toCompany(
			long companyId, Long fromEmployeeId, String type, String title, String body) {
		return toCompany(companyId, fromEmployeeId, type, title, body, null, null);
	}

	/**
	 * The same call with {@code $reference_type} and {@code $reference_id}.
	 *
	 * <p>{@code notification_employee_left_company_to_company()}
	 * ({@code helpers/notifications.php:253-267}) is the caller that needs
	 * them, and it passes something easy to misread: the reference is
	 * {@code 'employee'} plus the <em>departing employee's</em> id, which is
	 * also what it passes as {@code $from_employee_id}. So the same id appears
	 * in two columns of the row, deliberately -- the company's inbox entry both
	 * comes from that employee and points at them.
	 */
	public long toCompany(
			long companyId, Long fromEmployeeId, String type, String title, String body,
			String referenceType, Long referenceId) {
		KeyHolder keys = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			PreparedStatement statement =
					connection.prepareStatement(INSERT_COMPANY, PreparedStatement.RETURN_GENERATED_KEYS);
			statement.setLong(1, companyId);
			if (fromEmployeeId != null && fromEmployeeId > 0) {
				statement.setLong(2, fromEmployeeId);
			} else {
				statement.setNull(2, java.sql.Types.INTEGER);
			}
			statement.setString(3, title);
			statement.setString(4, body);
			statement.setString(5, type);
			statement.setString(6, referenceType);
			if (referenceId == null) {
				statement.setNull(7, java.sql.Types.INTEGER);
			} else {
				statement.setLong(7, referenceId);
			}
			return statement;
		}, keys);
		return keys.getKey() == null ? 0L : keys.getKey().longValue();
	}

	/**
	 * {@code notification_to_employee($company_id, $to, $type, $title, $body, $from)}.
	 *
	 * @param fromEmployeeId the acting employee; {@code notification_normalize_from()}
	 *        turns anything non-positive into SQL NULL
	 * @return the inserted notification id, as {@code get_last_inserted_id()} does
	 */
	public long toEmployee(
			long companyId, long toEmployeeId, Long fromEmployeeId, String type, String title, String body) {
		return toEmployee(companyId, toEmployeeId, fromEmployeeId, type, title, body, null, null);
	}

	/**
	 * The same call with {@code $reference_type} and {@code $reference_id}.
	 *
	 * <p>Two endpoints reach it, both passing {@code 'shift'} and a shift id:
	 * {@code employees/update.php:314} directly, for the one employee whose
	 * assignment changed, and {@code shifts/update.php:97} through
	 * {@link #broadcastToCompanyEmployees}, for every active employee of the
	 * company. {@code notification_insert()} casts the id with {@code (int)}
	 * while leaving the type as given.
	 */
	public long toEmployee(
			long companyId, long toEmployeeId, Long fromEmployeeId, String type, String title, String body,
			String referenceType, Long referenceId) {
		KeyHolder keys = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(INSERT, PreparedStatement.RETURN_GENERATED_KEYS);
			statement.setLong(1, companyId);
			if (fromEmployeeId != null && fromEmployeeId > 0) {
				statement.setLong(2, fromEmployeeId);
			} else {
				statement.setNull(2, java.sql.Types.INTEGER);
			}
			statement.setLong(3, toEmployeeId);
			statement.setString(4, title);
			statement.setString(5, body);
			statement.setString(6, type);
			statement.setString(7, referenceType);
			if (referenceId == null) {
				statement.setNull(8, java.sql.Types.INTEGER);
			} else {
				statement.setLong(8, referenceId);
			}
			return statement;
		}, keys);
		long notificationId = keys.getKey() == null ? 0L : keys.getKey().longValue();

		// try { sendPushToEmployee(...) } catch (Throwable $ignored) { }
		// -- delivery is best effort in legacy, and the row is already durable.
		// Wave 12.4 ships LegacyPushDeliveryUnavailable here; hr-platform#22
		// owns the real delivery and remains a Phase 1 cutover blocker.
		Map<String, String> payload = Map.of(
				"notification_id", String.valueOf(notificationId),
				"notification_type", type);
		try {
			pushDelivery.sendToEmployee(toEmployeeId, title, body == null ? "" : body, payload);
		} catch (Throwable ignored) { // NOPMD - PHP catches Throwable here, and that is the point
			// Deliberately empty and deliberately Throwable, matching
			// `catch (Throwable $ignored)`: once the row is inserted, *nothing*
			// the transport does may change the API outcome. The catch is
			// wrapped around this one call and nothing else -- no database work
			// and no part of the endpoint is inside it, so a genuine
			// application failure still surfaces.
		}
		return notificationId;
	}

}
