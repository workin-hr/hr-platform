package com.workin.legacy.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * {@code helpers/turnover_helper.php} -- employee turnover rate
 * (معدل دوران العمالة).
 *
 * <p>Per period: {@code end = start + new_hires - departures},
 * {@code average = (start + end) / 2}, {@code rate% = departures / average * 100}.
 *
 * <p>The effective hire date is {@code COALESCE(hire_date, DATE(created_at))}
 * everywhere, and a departure is {@code is_active = 0} dated by
 * {@code DATE(updated_at)}. <b>That makes {@code updated_at} load-bearing</b>:
 * any edit to a deactivated employee's row moves their departure date, and with
 * it the rate. The port keeps that rather than introducing a real termination
 * date, because a client comparing the two systems would otherwise see
 * different percentages for the same data.
 */
@Service
public class LegacyTurnover {

	/** {@code turnover_effective_hire_sql('e')}. */
	private static final String HIRE = "COALESCE(e.hire_date, DATE(e.created_at))";

	private final JdbcTemplate jdbcTemplate;

	public LegacyTurnover(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/**
	 * {@code turnover_rate_for_period()}.
	 *
	 * @param periodStart inclusive, {@code Y-m-d}
	 * @param periodEnd inclusive, {@code Y-m-d}
	 */
	public double rateForPeriod(long companyId, String periodStart, String periodEnd) {
		return rateFromCounts(
				countAtPeriodStart(companyId, periodStart),
				countNewHires(companyId, periodStart, periodEnd),
				countDepartures(companyId, periodStart, periodEnd, null, false));
	}

	/**
	 * {@code turnover_new_employee_90_day_rate()} -- turnover among employees
	 * hired in the last three months who left within 90 days of hire.
	 *
	 * <p>Its denominator is <b>not</b> the same shape as the period rate's: it
	 * averages the cohort's total against the cohort's <em>still-active</em>
	 * count, where the period rate averages the opening headcount against a
	 * computed closing one. Two formulas that both produce "a turnover
	 * percentage" and are not interchangeable.
	 */
	public double newEmployeeNinetyDayRate(long companyId, LocalDate today) {
		if (companyId <= 0) {
			return 0.0;
		}
		String periodEnd = today.toString();
		String periodStart = minusThreeMonthsPhpStyle(today).toString();

		int cohortTotal = countNewHires(companyId, periodStart, periodEnd);
		if (cohortTotal <= 0) {
			return 0.0;
		}

		Integer cohortActive = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM employees e"
						+ " WHERE e.company_id = ? AND " + HIRE + " >= ? AND " + HIRE + " <= ?"
						+ " AND e.is_active = 1",
				Integer.class, companyId, periodStart, periodEnd);

		int cohortDepartures = countDepartures(companyId, periodStart, periodEnd, 90, true);

		double average = (cohortTotal + (cohortActive == null ? 0 : cohortActive)) / 2.0;
		if (average <= 0.0) {
			return 0.0;
		}
		return round2(cohortDepartures / average * 100);
	}

	/**
	 * {@code strtotime('-3 months', ...)}, which is <b>not</b>
	 * {@link LocalDate#minusMonths}.
	 *
	 * <p>PHP keeps the day-of-month and lets it <em>roll</em> into the
	 * following month when the target month is shorter: 31 May minus three
	 * months is 31 February, which PHP resolves to 3 March. Java clamps to
	 * 28 February instead, which would put the cohort window's start up to
	 * three days earlier and quietly change who is in the cohort.
	 *
	 * <p>Reproduced by landing on the first of the target month and adding
	 * {@code day - 1} days, which rolls for exactly the same reason PHP does.
	 */
	static LocalDate minusThreeMonthsPhpStyle(LocalDate date) {
		LocalDate firstOfTargetMonth = date.withDayOfMonth(1).minusMonths(3);
		return firstOfTargetMonth.plusDays(date.getDayOfMonth() - 1L);
	}

	/**
	 * {@code turnover_count_at_period_start()}: hired strictly before the
	 * period, and either still active or departed on/after the period start.
	 */
	int countAtPeriodStart(long companyId, String periodStart) {
		if (companyId <= 0 || periodStart.isEmpty()) {
			return 0;
		}
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM employees e"
						+ " WHERE e.company_id = ? AND " + HIRE + " < ?"
						+ " AND (e.is_active = 1 OR (e.is_active = 0 AND DATE(e.updated_at) >= ?))",
				Integer.class, companyId, periodStart, periodStart);
		return count == null ? 0 : count;
	}

	/** {@code turnover_count_new_hires()}: effective hire date inside the period, both ends inclusive. */
	int countNewHires(long companyId, String periodStart, String periodEnd) {
		if (companyId <= 0 || periodStart.isEmpty() || periodEnd.isEmpty()) {
			return 0;
		}
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM employees e"
						+ " WHERE e.company_id = ? AND " + HIRE + " >= ? AND " + HIRE + " <= ?",
				Integer.class, companyId, periodStart, periodEnd);
		return count == null ? 0 : count;
	}

	/**
	 * {@code turnover_count_departures()}.
	 *
	 * @param maxDaysAfterHire when non-null, only departures within this many
	 *     days of the effective hire date -- interpolated into the SQL after an
	 *     {@code (int)} cast in PHP, so it is bound here as a validated int
	 *     rather than a parameter, matching the statement legacy issues
	 * @param cohortHiredInPeriodOnly when true, also restrict to employees
	 *     whose effective hire date falls inside the same period
	 */
	int countDepartures(long companyId, String periodStart, String periodEnd,
			Integer maxDaysAfterHire, boolean cohortHiredInPeriodOnly) {
		if (companyId <= 0 || periodStart.isEmpty() || periodEnd.isEmpty()) {
			return 0;
		}
		StringBuilder sql = new StringBuilder(
				"SELECT COUNT(*) FROM employees e"
						+ " WHERE e.company_id = ? AND e.is_active = 0"
						+ " AND DATE(e.updated_at) >= ? AND DATE(e.updated_at) <= ?");
		Object[] binds;
		if (cohortHiredInPeriodOnly) {
			sql.append(" AND ").append(HIRE).append(" >= ? AND ").append(HIRE).append(" <= ?");
			binds = new Object[] {companyId, periodStart, periodEnd, periodStart, periodEnd};
		} else {
			binds = new Object[] {companyId, periodStart, periodEnd};
		}
		if (maxDaysAfterHire != null) {
			sql.append(" AND DATEDIFF(DATE(e.updated_at), ").append(HIRE).append(") <= ")
					.append(maxDaysAfterHire.intValue());
		}
		Integer count = jdbcTemplate.queryForObject(sql.toString(), Integer.class, binds);
		return count == null ? 0 : count;
	}

	/**
	 * {@code turnover_rate_from_counts()}.
	 *
	 * <p>The closing headcount is floored at zero, so a period with more
	 * departures than {@code start + new_hires} does not produce a negative
	 * average -- it produces a smaller one, which <em>raises</em> the reported
	 * rate rather than lowering it.
	 */
	static double rateFromCounts(int start, int newHires, int departures) {
		int end = Math.max(0, start + newHires - departures);
		double average = (start + end) / 2.0;
		if (average <= 0.0) {
			return 0.0;
		}
		return round2(departures / average * 100);
	}

	/** {@code round($x, 2)} -- half away from zero, which for a non-negative rate is HALF_UP. */
	private static double round2(double value) {
		return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
	}
}
