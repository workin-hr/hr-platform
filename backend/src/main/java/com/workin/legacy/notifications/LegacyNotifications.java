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
	 * The same call with {@code $reference_type} and {@code $reference_id} --
	 * {@code employees/update.php}'s shift notification passes {@code 'shift'}
	 * and the shift id, and {@code notification_insert()} casts the id with
	 * {@code (int)} while leaving the type as given.
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
