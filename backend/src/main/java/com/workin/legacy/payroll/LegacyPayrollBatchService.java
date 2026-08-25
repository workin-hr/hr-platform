package com.workin.legacy.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyPagination;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.attendance.LegacyWeeklyOffDays;
import com.workin.legacy.wire.LegacyApiException;

/** {@code payroll_batches/*.php} (Wave 12.9). */
@Service
public class LegacyPayrollBatchService {

	/** {@code PayrollStatusEnum} ({@code config/enums.php}), the schema's own two values. */
	private static final String DRAFT = "draft";
	private static final String FINALIZED = "finalized";

	private final LegacyPayrollBatchStore store;
	private final LegacyPayrollFiscalSettings fiscalSettings;
	private final LegacyPayrollOvertimeSettings overtimeSettings;
	private final LegacyPayrollAttendanceFigures attendanceFigures;
	private final LegacyPayrollCalculationService calculationService;
	private final LegacyPayrollAdvanceDeductions advanceDeductions;
	private final LegacyWeeklyOffDays weeklyOffDays;
	private final LegacyClock clock;
	private final DataSource legacyDataSource;

	public LegacyPayrollBatchService(
			LegacyPayrollBatchStore store, LegacyPayrollFiscalSettings fiscalSettings,
			LegacyPayrollOvertimeSettings overtimeSettings, LegacyPayrollAttendanceFigures attendanceFigures,
			LegacyPayrollCalculationService calculationService, LegacyPayrollAdvanceDeductions advanceDeductions,
			LegacyWeeklyOffDays weeklyOffDays, LegacyClock clock, DataSource legacyDataSource) {
		this.store = store;
		this.fiscalSettings = fiscalSettings;
		this.overtimeSettings = overtimeSettings;
		this.attendanceFigures = attendanceFigures;
		this.calculationService = calculationService;
		this.legacyDataSource = legacyDataSource;
		this.advanceDeductions = advanceDeductions;
		this.weeklyOffDays = weeklyOffDays;
		this.clock = clock;
	}

	public record Page(List<Map<String, Object>> rows, Map<String, Object> meta) {
	}

	public Page list(long companyId, LegacyQueryParameters query) {
		LegacyPagination.Params page = LegacyPagination.params(query);
		String status = statusOrNull(query.value("status"));
		Integer year = yearOrNull(query.value("year"));
		String search = LegacyPagination.searchQueryParam(query);

		long total = store.countForList(companyId, status, year, search);
		List<Map<String, Object>> rows = store.list(companyId, status, year, search, page);
		return new Page(rows, LegacyPagination.meta(total, page));
	}

	public Map<String, Object> one(long companyId, long batchId) {
		Map<String, Object> row = store.withStats(batchId, companyId);
		if (row == null) {
			throw new LegacyApiException(404, "batch_not_found");
		}
		return row;
	}

	public Map<String, Object> create(long companyId, Map<String, Object> body) {
		int month = (int) requiredLong(body, "month");
		int year = (int) requiredLong(body, "year");
		String[] bounds = fiscalSettings.fiscalPeriodBounds(companyId, year, month);

		if (store.existsForPeriod(companyId, month, year)) {
			throw new LegacyApiException(400, "already_exists");
		}

		long id = store.insert(companyId, month, year, bounds[0], bounds[1], DRAFT);
		Map<String, Object> row = store.withStats(id, companyId);
		if (row == null) {
			throw new LegacyApiException(404, "batch_not_found");
		}
		return row;
	}

	public Map<String, Object> update(long companyId, long batchId, Map<String, Object> body) {
		Map<String, Object> batch = store.scoped(batchId, companyId);
		if (batch == null) {
			throw new LegacyApiException(404, "batch_not_found");
		}
		if (FINALIZED.equals(batch.get("status"))) {
			throw new LegacyApiException(400, "batch_already_finalized");
		}

		int month = body.get("month") != null
				? (int) LegacyValues.toPhpLong(body.get("month")) : ((Number) batch.get("month")).intValue();
		int year = body.get("year") != null
				? (int) LegacyValues.toPhpLong(body.get("year")) : (int) LegacyValues.toPhpLong(batch.get("year"));
		String[] bounds = fiscalSettings.fiscalPeriodBounds(companyId, year, month);

		store.updatePeriod(batchId, month, year, bounds[0], bounds[1]);
		Map<String, Object> row = store.withStats(batchId, companyId);
		if (row == null) {
			throw new LegacyApiException(404, "batch_not_found");
		}
		return row;
	}

	public void delete(long companyId, long batchId) {
		Map<String, Object> batch = store.scoped(batchId, companyId);
		if (batch == null) {
			throw new LegacyApiException(404, "batch_not_found");
		}
		if (FINALIZED.equals(batch.get("status"))) {
			throw new LegacyApiException(400, "batch_already_finalized");
		}
		store.deleteWithPayslips(batchId);
	}

	/** {@code fiscal_period.php}: bounds plus the company-wide (no employee override) working-day count. */
	public Map<String, Object> fiscalPeriod(long companyId, int year, int month) {
		if (year < 2000 || month < 1 || month > 12) {
			throw new LegacyApiException(400, "invalid_input");
		}
		String[] bounds = fiscalSettings.fiscalPeriodBounds(companyId, year, month);
		int workingDays = periodWorkingDays(companyId, bounds[0], bounds[1]);

		return new java.util.LinkedHashMap<>(Map.of(
				"period_from", bounds[0],
				"period_to", bounds[1],
				"year", year,
				"month", month,
				"working_days", workingDays));
	}

	/**
	 * {@code payroll_period_working_days()} ({@code payroll_calculation.php:151-186}),
	 * the no-{@code employee_id} branch only -- the shift-override path is a
	 * per-employee concern the calculation engine slice owns.
	 */
	private int periodWorkingDays(long companyId, String from, String to) {
		java.time.LocalDate start = java.time.LocalDate.parse(from);
		java.time.LocalDate end = java.time.LocalDate.parse(to);
		if (end.isBefore(start)) {
			return 0;
		}
		List<String> rest = weeklyOffDays.forCompany(companyId);
		int count = 0;
		for (java.time.LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
			int dayOfWeek = day.getDayOfWeek().getValue() % 7; // PHP's date('w'): 0 = Sunday
			if (!LegacyWeeklyOffDays.isWeeklyRestDay(dayOfWeek, rest)) {
				count++;
			}
		}
		return Math.max(0, count);
	}

	// ------------------------------------------------------------------
	// calculate.php / finalize.php / reopen.php / stats.php
	// ------------------------------------------------------------------

	/** {@code calculate.php}. @return the batch row plus the recalculated-employee count. */
	public CalculationResult calculate(long companyId, long batchId, String weeklyRestLabel) {
		Map<String, Object> batch = store.scoped(batchId, companyId);
		if (batch == null) {
			throw new LegacyApiException(404, "batch_not_found");
		}
		if (FINALIZED.equals(batch.get("status"))) {
			throw new LegacyApiException(400, "batch_already_finalized");
		}

		int calculatedCount = calculateBatch(companyId, batchId, batch, weeklyRestLabel);

		Map<String, Object> row = store.withStats(batchId, companyId);
		if (row == null) {
			throw new LegacyApiException(404, "batch_not_found");
		}
		return new CalculationResult(row, calculatedCount);
	}

	public record CalculationResult(Map<String, Object> row, int calculatedCount) {
	}

	/**
	 * {@code payroll_calculate_batch()} ({@code payroll_calculation.php:1283-1431}):
	 * refresh the fiscal period, delete and recompute every active
	 * employee's payslip. Idempotent by design -- a second call for the
	 * same batch reproduces the same rows, not additive ones.
	 */
	private int calculateBatch(long companyId, long batchId, Map<String, Object> batch, String weeklyRestLabel) {
		int year = (int) LegacyValues.toPhpLong(batch.get("year"));
		int month = ((Number) batch.get("month")).intValue();
		String[] bounds = fiscalSettings.fiscalPeriodBounds(companyId, year, month);
		store.updatePeriod(batchId, month, year, bounds[0], bounds[1]);

		double overtimeMultiplier = overtimeSettings.overtimeMultiplier(companyId);
		boolean paysOvertime = overtimeSettings.companyPaysOvertime(companyId);
		String periodFrom = bounds[0];
		String periodTo = bounds[1];

		store.deletePayslipsForBatch(batchId);

		int calculated = 0;
		for (long employeeId : store.activeEmployeeIds(companyId)) {
			Map<String, Object> contract = store.effectiveContract(employeeId, periodTo);
			if (contract == null) {
				continue;
			}
			LegacyPayrollCalculationService.PayslipComputation computed = computeEmployeePayslip(
					companyId, employeeId, contract, periodFrom, periodTo, year, month,
					overtimeMultiplier, paysOvertime, weeklyRestLabel);
			store.insertPayslip(batchId, employeeId, computed);
			calculated++;
		}
		return calculated;
	}

	/** {@code payroll_compute_employee_payslip()} ({@code payroll_calculation.php:1101-1276}). */
	private LegacyPayrollCalculationService.PayslipComputation computeEmployeePayslip(
			long companyId, long employeeId, Map<String, Object> contract, String periodFrom, String periodTo,
			int batchYear, int batchMonth, double overtimeMultiplier, boolean paysOvertime, String weeklyRestLabel) {

		String today = clock.todayAsString();
		String asOf = today.compareTo(periodTo) <= 0 ? today : periodTo;
		boolean inProgress = asOf.compareTo(periodTo) < 0;
		String rangeTo = inProgress ? asOf : periodTo;

		LegacyPayrollAttendanceFigures.AttendanceSummary summary =
				attendanceFigures.attendanceSummary(companyId, employeeId, periodFrom, rangeTo, weeklyRestLabel);
		LegacyPayrollAttendanceFigures.AttendanceDisplay display = attendanceFigures.attendanceDisplay(
				companyId, employeeId, periodFrom, periodTo, summary.daysPresent(), asOf);
		BigDecimal workHoursPerDay = attendanceFigures.employeeWorkHoursPerDay(employeeId);

		LegacyPayrollCalculationService.Contract contractView = new LegacyPayrollCalculationService.Contract(
				normalizedSalaryMode(contract.get("salary_mode")),
				decimal(contract.get("basic_salary")), decimal(contract.get("daily_wage")),
				decimal(contract.get("housing_allowance")), decimal(contract.get("transport_allowance")),
				decimal(contract.get("food_allowance")), decimal(contract.get("risk_allowance")),
				decimal(contract.get("incentives")), decimal(contract.get("insurance_deduction")),
				decimal(contract.get("tax_deduction")), decimal(contract.get("advances_deduction")),
				decimal(contract.get("fund_deduction")));

		LegacyPayrollCalculationService.AttendanceFigures figures = new LegacyPayrollCalculationService.AttendanceFigures(
				summary.daysPresent(), BigDecimal.valueOf(summary.totalHours()), display.daysAbsent(),
				display.daysLeave(), display.earnedWeeklyRestDays(), display.officialHolidayDays(), workHoursPerDay);

		int elapsedCalendarDays = 0;
		if (inProgress) {
			LocalDate from = LocalDate.parse(periodFrom);
			LocalDate asOfDate = LocalDate.parse(asOf);
			elapsedCalendarDays = asOfDate.isBefore(from) ? 0 : (int) (ChronoUnit.DAYS.between(from, asOfDate) + 1);
		}
		LegacyPayrollCalculationService.PeriodProgress progress =
				new LegacyPayrollCalculationService.PeriodProgress(inProgress, elapsedCalendarDays);

		BigDecimal penaltyDays = store.unappliedPenaltyDays(employeeId, periodFrom, periodTo);
		List<Map<String, Object>> advances = store.approvedAdvances(employeeId);
		BigDecimal advanceDeduction = advanceDeductions.deductionForBatch(advances, batchYear, batchMonth);

		LegacyPayrollCalculationService.OvertimePolicy overtimePolicy =
				new LegacyPayrollCalculationService.OvertimePolicy(BigDecimal.valueOf(overtimeMultiplier), paysOvertime);

		return calculationService.compute(contractView, figures, penaltyDays, advanceDeduction, progress, overtimePolicy);
	}

	/** {@code $salary_mode = $contract[SALARY_MODE] ?? 'monthly'; if not 'daily' or 'monthly', 'monthly'.} */
	private static String normalizedSalaryMode(Object raw) {
		String mode = raw == null ? "monthly" : LegacyValues.toPhpString(raw);
		return "daily".equals(mode) ? "daily" : "monthly";
	}

	private static BigDecimal decimal(Object raw) {
		return raw == null ? BigDecimal.ZERO : LegacyValues.toPhpDecimal(raw);
	}

	/**
	 * {@code finalize.php}: apply advance payments, mark penalties applied,
	 * transactionally. The batch fetch and status checks precede {@code
	 * beginTransaction} in PHP but use the same connection the writes later
	 * use; here, since the checks read via the pooled {@link #store} rather
	 * than the not-yet-open single connection, they simply run first on
	 * their own (pooled) connection -- equivalent for a fetch-then-write
	 * sequence where nothing else can finalize the same batch concurrently
	 * through this single-instance flow, matching {@code
	 * LegacyRequestApprovalService.approve()}'s own precedent (D-100).
	 */
	public Map<String, Object> finalize(long companyId, long batchId) {
		Map<String, Object> batch = store.scoped(batchId, companyId);
		if (batch == null) {
			throw new LegacyApiException(404, "batch_not_found");
		}
		if (FINALIZED.equals(batch.get("status"))) {
			throw new LegacyApiException(400, "batch_already_finalized");
		}
		int year = (int) LegacyValues.toPhpLong(batch.get("year"));
		int month = ((Number) batch.get("month")).intValue();
		String[] bounds = fiscalSettings.fiscalPeriodBounds(companyId, year, month);

		inTransaction(txStore -> {
			txStore.finalizeBatch(batchId, FINALIZED, bounds[0], bounds[1]);
			applyAdvancePaymentsAndMarkPenalties(txStore, batchId, bounds[0], bounds[1], year, month);
		});

		Map<String, Object> row = store.withStats(batchId, companyId);
		if (row == null) {
			throw new LegacyApiException(404, "batch_not_found");
		}
		return row;
	}

	/** {@code payroll_finalize_batch_side_effects()} ({@code payroll_calculation.php:1021-1059}). */
	private void applyAdvancePaymentsAndMarkPenalties(
			LegacyPayrollBatchStore txStore, long batchId, String periodFrom, String periodTo, int year, int month) {
		for (Map<String, Object> payslip : txStore.payslipsForBatch(batchId)) {
			long employeeId = LegacyValues.toPhpLong(payslip.get("employee_id"));
			List<Map<String, Object>> advances = txStore.approvedAdvances(employeeId);
			for (LegacyPayrollAdvanceDeductions.DeductionItem item
					: advanceDeductions.itemsForBatch(advances, year, month)) {
				applyAdvancePayment(txStore, item.advanceId(), item.amount());
			}
		}
		if (!periodFrom.isEmpty() && !periodTo.isEmpty()) {
			txStore.markPenaltiesAppliedForBatch(batchId, periodFrom, periodTo);
		}
	}

	/** {@code payroll_apply_advance_payment()} ({@code payroll_calculation.php:960-979}). */
	private void applyAdvancePayment(LegacyPayrollBatchStore txStore, long advanceId, BigDecimal amount) {
		if (advanceId <= 0 || amount.signum() <= 0) {
			return;
		}
		BigDecimal remaining = txStore.advanceRemaining(advanceId);
		if (remaining == null) {
			return;
		}
		BigDecimal newRemaining = remaining.subtract(amount).setScale(2, RoundingMode.HALF_UP);
		if (newRemaining.signum() < 0) {
			newRemaining = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
		}
		txStore.updateAdvanceRemaining(advanceId, newRemaining);
	}

	/** {@code reopen.php}: reverse {@code finalize}'s side effects, transactionally. */
	public Map<String, Object> reopen(long companyId, long batchId) {
		Map<String, Object> batch = store.scoped(batchId, companyId);
		if (batch == null) {
			throw new LegacyApiException(404, "batch_not_found");
		}
		if (!FINALIZED.equals(batch.get("status"))) {
			throw new LegacyApiException(400, "batch_not_finalized");
		}
		int year = (int) LegacyValues.toPhpLong(batch.get("year"));
		int month = ((Number) batch.get("month")).intValue();
		String periodFrom = LegacyValues.toPhpString(batch.get("period_from"));
		String periodTo = LegacyValues.toPhpString(batch.get("period_to"));

		inTransaction(txStore -> {
			restoreAdvancesAndUnmarkPenalties(txStore, batchId, periodFrom, periodTo, year, month);
			txStore.updateStatus(batchId, DRAFT);
		});

		Map<String, Object> row = store.withStats(batchId, companyId);
		if (row == null) {
			throw new LegacyApiException(404, "batch_not_found");
		}
		return row;
	}

	/** {@code payroll_reopen_batch_side_effects()} ({@code payroll_calculation.php:1061-1099}). */
	private void restoreAdvancesAndUnmarkPenalties(
			LegacyPayrollBatchStore txStore, long batchId, String periodFrom, String periodTo, int year, int month) {
		for (Map<String, Object> payslip : txStore.payslipsForBatch(batchId)) {
			long employeeId = LegacyValues.toPhpLong(payslip.get("employee_id"));
			BigDecimal deducted = decimal(payslip.get("advance_deduction"));
			restoreAdvanceDeductions(txStore, employeeId, deducted, year, month);
		}
		if (!periodFrom.isEmpty() && !periodTo.isEmpty()) {
			txStore.unmarkPenaltiesAppliedForBatch(batchId, periodFrom, periodTo);
		}
	}

	/** {@code payroll_restore_advance_deductions()} ({@code payroll_calculation.php:981-1019}). */
	private void restoreAdvanceDeductions(
			LegacyPayrollBatchStore txStore, long employeeId, BigDecimal deductedTotal, int year, int month) {
		if (deductedTotal.signum() <= 0) {
			return;
		}
		List<Map<String, Object>> advances = txStore.approvedAdvances(employeeId);
		for (LegacyPayrollAdvanceDeductions.RestoreShare share
				: advanceDeductions.restoreShares(advances, deductedTotal, year, month)) {
			if (share.amount().signum() > 0) {
				txStore.addAdvanceRemaining(share.advanceId(), share.amount());
			}
		}
	}

	/**
	 * Opens one physical connection, flips it to manual-commit, runs {@code work}
	 * against a {@link LegacyPayrollBatchStore} scoped to that single connection
	 * (via {@link SingleConnectionDataSource}, {@code suppressClose=true} so the
	 * store's own {@code JdbcTemplate} calls never release it early), then
	 * commits or rolls back and restores the connection's original autocommit
	 * state -- the same open/flip/commit-or-rollback/restore shape {@code
	 * LegacyRequestApprovalService.approve()} uses (D-100), reused here via a
	 * scoped {@code Store} instance instead of connection-parameterized methods
	 * since {@link LegacyPayrollBatchStore}'s methods are plain {@code
	 * JdbcTemplate} calls rather than raw JDBC throughout.
	 */
	private void inTransaction(java.util.function.Consumer<LegacyPayrollBatchStore> work) {
		try (Connection connection = legacyDataSource.getConnection()) {
			boolean previousAutoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			try {
				SingleConnectionDataSource scoped = new SingleConnectionDataSource(connection, true);
				work.accept(new LegacyPayrollBatchStore(scoped));
				connection.commit();
			} catch (Throwable failure) { // NOPMD -- PHP's beginTransaction()/rollBack() catches Throwable too
				try {
					connection.rollback();
				} catch (Throwable rollbackFailure) {
					rollbackFailure.addSuppressed(failure);
					throw rollbackFailure instanceof RuntimeException re ? re : new RuntimeException(rollbackFailure);
				}
				throw failure instanceof RuntimeException re ? re : new RuntimeException(failure);
			} finally {
				try {
					connection.setAutoCommit(previousAutoCommit);
				} catch (Throwable ignored) {
					// Connection close follows immediately; do not replace a business failure.
				}
			}
		} catch (java.sql.SQLException ex) {
			throw new RuntimeException(ex);
		}
	}

	/** {@code stats.php}. */
	public Map<String, Object> stats(long companyId, long batchId) {
		Map<String, Object> batch = store.scoped(batchId, companyId);
		if (batch == null) {
			throw new LegacyApiException(404, "batch_not_found");
		}
		Map<String, Object> stats = store.statsForBatch(batchId);

		java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
		result.put("batch_id", batchId);
		result.put("period_from", batch.get("period_from"));
		result.put("period_to", batch.get("period_to"));
		result.put("status", batch.get("status"));
		result.put("month", batch.get("month"));
		result.put("year", batch.get("year"));
		result.put("total_employees", intOrZero(stats.get("total_employees")));
		result.put("total_basic_salary", decimal(stats.get("total_basic_salary")));
		result.put("total_allowances", decimal(stats.get("total_allowances")));
		result.put("total_overtime_pay", decimal(stats.get("total_overtime_pay")));
		result.put("total_entitlements", decimal(stats.get("total_entitlements")));
		result.put("total_deductions", decimal(stats.get("total_deductions")));
		result.put("total_penalties", decimal(stats.get("total_penalties")));
		result.put("total_advance_deductions", decimal(stats.get("total_advance_deductions")));
		result.put("total_other_deductions", decimal(stats.get("total_other_deductions")));
		result.put("total_net_salary", decimal(stats.get("total_net_salary")));
		result.put("avg_net_salary", decimal(stats.get("avg_net_salary")).setScale(2, RoundingMode.HALF_UP));
		result.put("max_net_salary", decimal(stats.get("max_net_salary")));
		result.put("min_net_salary", decimal(stats.get("min_net_salary")));
		result.put("total_days_present", intOrZero(stats.get("total_days_present")));
		result.put("total_days_absent", intOrZero(stats.get("total_days_absent")));
		result.put("total_days_leave", intOrZero(stats.get("total_days_leave")));
		result.put("total_overtime_hours", decimal(stats.get("total_overtime_hours")).setScale(2, RoundingMode.HALF_UP));
		return result;
	}

	private static int intOrZero(Object raw) {
		return raw == null ? 0 : (int) LegacyValues.toPhpLong(raw);
	}

	private static String statusOrNull(Object raw) {
		return raw == null ? null : LegacyValues.toPhpString(raw);
	}

	private static Integer yearOrNull(Object raw) {
		long year = raw == null ? 0 : LegacyValues.toPhpLong(raw);
		return year > 0 ? (int) year : null;
	}

	/** {@code required()} ({@code functions.php:617-623}): {@code isset()} plus a bare empty-string check. */
	private static long requiredLong(Map<String, Object> body, String key) {
		Object value = body.get(key);
		if (value == null || "".equals(value)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", key));
		}
		return LegacyValues.toPhpLong(value);
	}
}
