package com.workin.legacy.attendance.location;

import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.workin.legacy.LegacyJdbcValues;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.wire.LegacyApiException;

/**
 * The attendance geofence: {@code attendance_location_helper.php:1-186} plus
 * the two branch-eligibility helpers from {@code functions.php:946-1023}.
 *
 * <h2>Two policies, chosen per employee</h2>
 * <p>{@code employee_can_check_in_any_branch()} decides which:
 *
 * <ul>
 *   <li><b>any-branch</b> -- every active company branch with GPS is a
 *       candidate, and being inside <em>any</em> of them passes;</li>
 *   <li><b>assigned-branch</b> -- only the employee's own branch counts, and it
 *       must be active and have GPS.</li>
 * </ul>
 *
 * <h2>{@code require_location_configured} is the D-092 bypass, not a
 * convenience</h2>
 * <p>It is the fifth parameter, and {@code check_in.php} passes
 * {@code $is_self_attendance} into it. So the <em>same</em> unconfigured branch
 * is a hard 403 for an employee checking themselves in and is silently skipped
 * for an admin doing it on their behalf. That asymmetry is deliberate here and
 * is not normalised into one rule: it is exactly what D-092 records.
 *
 * <p>{@code check_out.php} reaches the validator differently -- it passes four
 * arguments, so the flag defaults to {@code true}, but it only calls the
 * validator at all when the caller is acting on themselves. Same bypass, a
 * different shape.
 *
 * <h2>The distance failure is not a 403</h2>
 * <p>Being out of range is {@code out_of_range} <b>400</b> carrying the rounded
 * distance and the radius as message placeholders, while a misconfigured branch
 * is 403 and a missing one is 404. Three failures, three statuses.
 */
@Component
public class LegacyAttendanceLocation {

	/** {@code branch_attendance_radius_meters()}'s default, used for both the column and the nearest-branch report. */
	private static final int DEFAULT_RADIUS_METERS = 200;

	private static final int EARTH_RADIUS_METERS = 6_371_000;

	private final JdbcTemplate jdbcTemplate;

	public LegacyAttendanceLocation(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/**
	 * {@code validate_employee_attendance_location()}. Returns normally when the
	 * position is acceptable; throws the legacy failure otherwise.
	 *
	 * @param requireLocationConfigured PHP's fifth argument -- when false, a
	 *        company or branch with no GPS configured is silently accepted
	 */
	public void validate(
			Map<String, Object> employee, long companyId, double latitude, double longitude,
			boolean requireLocationConfigured) {
		if (canCheckInAnyBranch(employee)) {
			validateAgainstAnyBranch(companyId, latitude, longitude, requireLocationConfigured);
			return;
		}

		Long assignedBranchId = normalizeOptionalBranchId(employee.get("branch_id"));
		if (assignedBranchId == null) {
			if (requireLocationConfigured) {
				throw new LegacyApiException(403, "employee_branch_required");
			}
			return;
		}

		List<Map<String, Object>> rows = jdbcTemplate.query("""
				SELECT latitude, longitude, radius_meters FROM branches
				WHERE id = ? AND company_id = ? AND is_active = 1""",
				LegacyJdbcValues.rowMapper(), assignedBranchId, companyId);
		if (rows.isEmpty()) {
			if (requireLocationConfigured) {
				throw new LegacyApiException(404, "branch_not_found");
			}
			return;
		}
		Map<String, Object> branch = rows.get(0);
		if (!hasAttendanceLocation(branch)) {
			if (requireLocationConfigured) {
				throw new LegacyApiException(403, "branch_location_not_configured");
			}
			return;
		}

		double distance = haversineMeters(latitude, longitude,
				toDouble(branch.get("latitude")), toDouble(branch.get("longitude")));
		long radius = radiusMeters(branch);
		if (distance > radius) {
			throw outOfRange(distance, radius);
		}
	}

	/**
	 * The any-branch arm.
	 *
	 * <p>Two details are legacy's and both are observable. The loop
	 * {@code break}s on the first branch that contains the point, so the
	 * reported nearest branch is only ever the nearest among those checked
	 * <em>before</em> a match -- irrelevant on success, and the reason the
	 * failure message names whichever branch happened to be nearest so far. And
	 * when the company has no located branch at all and the flag is false, the
	 * position is accepted without any check: there is nothing to compare
	 * against.
	 */
	/**
	 * {@code employee_row_attach_attendance_location_flag(&$employee, $company_id)}
	 * ({@code helpers/attendance_location_helper.php:59-64}).
	 *
	 * <p>It writes {@code branch_location_configured} <b>only</b> when
	 * cross-branch attendance is on for this employee. When it is off the key is
	 * left exactly as the endpoint's own projection produced it -- which for
	 * {@code profile/employee.php} is the SQL expression over the employee's
	 * assigned branch, and for the login response is nothing at all. So the
	 * absence of a write is as much part of the contract as the write.
	 *
	 * <p>The truth test is {@link #canCheckInAnyBranch}, legacy's literal
	 * {@code true|1|'1'|'true'} set, not general truthiness.
	 *
	 * @param employee mutated in place, as PHP's by-reference parameter is
	 */
	public void attachBranchLocationConfiguredFlag(Map<String, Object> employee, long companyId) {
		if (!canCheckInAnyBranch(employee)) {
			return;
		}
		Long configured = jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM branches
				WHERE company_id = ? AND is_active = 1
				  AND latitude IS NOT NULL AND longitude IS NOT NULL""",
				Long.class, companyId);
		employee.put("branch_location_configured", configured != null && configured > 0 ? 1L : 0L);
	}

	private void validateAgainstAnyBranch(
			long companyId, double latitude, double longitude, boolean requireLocationConfigured) {
		List<Map<String, Object>> branches = jdbcTemplate.query("""
				SELECT id, latitude, longitude, radius_meters FROM branches
				WHERE company_id = ? AND is_active = 1
				  AND latitude IS NOT NULL AND longitude IS NOT NULL""",
				LegacyJdbcValues.rowMapper(), companyId);
		if (branches.isEmpty()) {
			if (requireLocationConfigured) {
				throw new LegacyApiException(403, "branch_location_not_configured");
			}
			return;
		}

		Double nearestDistance = null;
		long nearestRadius = DEFAULT_RADIUS_METERS;
		for (Map<String, Object> branch : branches) {
			double distance = haversineMeters(latitude, longitude,
					toDouble(branch.get("latitude")), toDouble(branch.get("longitude")));
			long radius = radiusMeters(branch);
			if (distance <= radius) {
				return;
			}
			if (nearestDistance == null || distance < nearestDistance) {
				nearestDistance = distance;
				nearestRadius = radius;
			}
		}
		if (nearestDistance != null) {
			throw outOfRange(nearestDistance, nearestRadius);
		}
	}

	/**
	 * {@code parse_request_gps_coordinates()}
	 * ({@code attendance_location_helper.php:166-186}).
	 *
	 * <p>Reads {@code latitude}/{@code longitude} from the body, falls back to
	 * the open check-in row's stored coordinates when either is absent or empty,
	 * and only then fails. So a check-out with no GPS at all reuses <em>the
	 * coordinates the employee checked in from</em> -- which means a check-out
	 * from anywhere passes the geofence as long as the check-in did.
	 *
	 * <p>The absent/empty test is {@code === null || === ''}: the string
	 * {@code "0"} is a supplied coordinate and does not trigger the fallback.
	 *
	 * @return {@code [latitude, longitude]}
	 */
	public static double[] parseRequestCoordinates(
			Map<String, Object> body, Map<String, Object> fallbackRow) {
		Object latitude = body == null ? null : body.get("latitude");
		Object longitude = body == null ? null : body.get("longitude");

		if (isBlank(latitude) || isBlank(longitude)) {
			if (fallbackRow != null) {
				latitude = fallbackRow.get("latitude");
				longitude = fallbackRow.get("longitude");
			}
		}
		if (isBlank(latitude) || isBlank(longitude)) {
			throw new LegacyApiException(400, "gps_coordinates_required");
		}
		if (!isNumeric(latitude) || !isNumeric(longitude)) {
			// The field placeholder is always `latitude`, even when it is the
			// longitude that is unparseable.
			throw new LegacyApiException(400, "invalid_input", null, Map.of("field", "latitude"));
		}
		return new double[] {toDouble(latitude), toDouble(longitude)};
	}

	/**
	 * {@code employee_may_check_in_at_branch()} ({@code functions.php:976-1000}).
	 *
	 * <p>An any-branch employee may use any active company branch. Otherwise the
	 * assigned branch must match exactly -- and an employee with <em>no</em>
	 * assigned branch may use any active company branch, which is the same
	 * outcome by a different route.
	 */
	public boolean mayCheckInAtBranch(Map<String, Object> employee, long branchId, long companyId) {
		if (canCheckInAnyBranch(employee)) {
			return branchIsActive(branchId, companyId);
		}
		Long assigned = normalizeOptionalBranchId(employee.get("branch_id"));
		if (assigned != null) {
			return assigned == branchId;
		}
		return branchIsActive(branchId, companyId);
	}

	/**
	 * {@code employee_can_check_in_any_branch()} ({@code functions.php:1016-1023}).
	 *
	 * <p>The rule depends on the deployed schema. {@code employees_has_column()}
	 * asks {@code INFORMATION_SCHEMA} whether {@code can_check_in_any_branch}
	 * exists; if it does, the flag decides, and if it does not, a completely
	 * different rule applies -- an employee with no assigned branch may check in
	 * anywhere.
	 *
	 * <p>The vendored schema <b>does</b> carry the column, so the flag branch is
	 * the reachable one. The fallback is kept anyway: the probe is real runtime
	 * logic, not a migration artefact, and deleting the branch would misreport
	 * what the helper does. It is not generalised into a schema-compatibility
	 * abstraction -- one probe, one place.
	 *
	 * <p>The truthiness test is legacy's own literal set,
	 * {@code true|1|'1'|'true'}, not PHP's general truthiness: a
	 * {@code tinyint(1)} of 2 would be false here.
	 */
	public boolean canCheckInAnyBranch(Map<String, Object> employee) {
		if (!employeesHasColumn("can_check_in_any_branch")) {
			return normalizeOptionalBranchId(employee.get("branch_id")) == null;
		}
		Object flag = employee.get("can_check_in_any_branch");
		if (flag == null) {
			return false;
		}
		if (flag instanceof Boolean bool) {
			return bool;
		}
		if (flag instanceof Number number) {
			return number.longValue() == 1L;
		}
		String text = String.valueOf(flag);
		return "1".equals(text) || "true".equals(text);
	}

	/**
	 * {@code employees_has_column()} ({@code functions.php:1004-1014}), including
	 * its per-process static cache.
	 */
	private boolean employeesHasColumn(String column) {
		return COLUMN_CACHE.computeIfAbsent(column, name -> {
			Integer count = jdbcTemplate.queryForObject("""
					SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
					WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?""",
					Integer.class, "employees", name);
			return count != null && count > 0;
		});
	}

	private static final Map<String, Boolean> COLUMN_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

	/** {@code normalize_optional_branch_id()}: null, empty and non-positive all become null. */
	public static Long normalizeOptionalBranchId(Object value) {
		if (value == null || "".equals(value)) {
			return null;
		}
		long id = LegacyValues.toPhpLong(value);
		return id > 0 ? id : null;
	}

	/**
	 * {@code branch_attendance_radius_meters()}: a non-positive radius becomes
	 * 200. Kept at 64-bit width: {@code radius_meters} is an unsigned database
	 * column, so a value above {@link Integer#MAX_VALUE} is legitimate and
	 * must not wrap negative and fall back to the default.
	 */
	public static long radiusMeters(Map<String, Object> branch) {
		Object raw = branch == null ? null : branch.get("radius_meters");
		long radius = raw == null ? DEFAULT_RADIUS_METERS : LegacyValues.toPhpLong(raw);
		return radius > 0 ? radius : DEFAULT_RADIUS_METERS;
	}

	/** {@code branch_has_attendance_location()}: both coordinates present and non-empty. */
	public static boolean hasAttendanceLocation(Map<String, Object> branch) {
		if (branch == null) {
			return false;
		}
		return !isBlank(branch.get("latitude")) && !isBlank(branch.get("longitude"));
	}

	/** {@code calculate_haversine_distance()}, in metres. */
	public static double haversineMeters(
			double latitude1, double longitude1, double latitude2, double longitude2) {
		double deltaLatitude = Math.toRadians(latitude2 - latitude1);
		double deltaLongitude = Math.toRadians(longitude2 - longitude1);
		double a = Math.pow(Math.sin(deltaLatitude / 2), 2)
				+ Math.cos(Math.toRadians(latitude1)) * Math.cos(Math.toRadians(latitude2))
				* Math.pow(Math.sin(deltaLongitude / 2), 2);
		return EARTH_RADIUS_METERS * 2 * Math.asin(Math.sqrt(a));
	}

	/** {@code fail_out_of_attendance_range()}: 400, with the distance rounded to whole metres. */
	private static LegacyApiException outOfRange(double distanceMeters, long radiusMeters) {
		return new LegacyApiException(400, "out_of_range", null, Map.of(
				"dist", String.valueOf((long) Math.round(distanceMeters)),
				"radius", String.valueOf(radiusMeters)));
	}

	private boolean branchIsActive(long branchId, long companyId) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM branches WHERE id = ? AND company_id = ? AND is_active = 1",
				Integer.class, branchId, companyId);
		return count != null && count > 0;
	}

	/** PHP's {@code $v === null || $v === ''}. */
	private static boolean isBlank(Object value) {
		return value == null || "".equals(value);
	}

	private static boolean isNumeric(Object value) {
		if (value instanceof Number) {
			return true;
		}
		return String.valueOf(value).trim()
				.matches("^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?$");
	}

	private static double toDouble(Object value) {
		if (value instanceof Number number) {
			return number.doubleValue();
		}
		return Double.parseDouble(String.valueOf(value).trim());
	}

}
