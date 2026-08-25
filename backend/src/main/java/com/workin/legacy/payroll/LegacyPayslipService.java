package com.workin.legacy.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyPagination;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.wire.LegacyApiException;

/** {@code payslips/*.php} (Wave 12.9 slice 3). */
@Service
public class LegacyPayslipService {

	/** {@code PayrollStatusEnum::FINALIZED} and {@code PENALTY_CALENDAR_DAYS_PER_MONTH}. */
	private static final String FINALIZED = "finalized";
	private static final int MONTH_DAYS = 30;

	private final LegacyPayslipStore store;
	private final LegacyPayrollBatchStore batchStore;
	private final LegacyPayrollAttendanceFigures attendanceFigures;
	private final LegacyPayrollOvertimeSettings overtimeSettings;
	private final LegacyClock clock;

	public LegacyPayslipService(
			LegacyPayslipStore store, LegacyPayrollBatchStore batchStore,
			LegacyPayrollAttendanceFigures attendanceFigures, LegacyPayrollOvertimeSettings overtimeSettings,
			LegacyClock clock) {
		this.store = store;
		this.batchStore = batchStore;
		this.attendanceFigures = attendanceFigures;
		this.overtimeSettings = overtimeSettings;
		this.clock = clock;
	}

	// ------------------------------------------------------------------
	// create.php
	// ------------------------------------------------------------------

	/** {@code create.php}: no attendance recompute -- every figure comes from the request body. */
	public Map<String, Object> create(long companyId, Map<String, Object> body) {
		long batchId = requiredLong(body, "batch_id");
		long employeeId = requiredLong(body, "employee_id");

		Map<String, Object> batch = batchStore.scoped(batchId, companyId);
		if (batch == null) {
			throw new LegacyApiException(404, "batch_not_found");
		}
		if (FINALIZED.equals(batch.get("status"))) {
			throw new LegacyApiException(400, "batch_already_finalized");
		}

		Map<String, Object> employee = store.employee(employeeId, companyId);
		if (employee == null) {
			throw new LegacyApiException(404, "employee_not_found");
		}
		if (store.existsForBatchEmployee(batchId, employeeId)) {
			throw new LegacyApiException(400, "already_exists");
		}

		String periodTo = LegacyValues.toPhpString(batch.get("period_to"));
		Map<String, Object> contract = batchStore.effectiveContract(employeeId, periodTo);

		BigDecimal basicSalary = contract == null ? BigDecimal.ZERO : decimal(contract.get("basic_salary"));
		BigDecimal allowances = contract == null ? BigDecimal.ZERO : decimal(contract.get("transport_allowance"))
				.add(decimal(contract.get("food_allowance")))
				.add(decimal(contract.get("risk_allowance")))
				.add(decimal(contract.get("incentives")));

		long daysPresent = optionalLong(body, "days_present");
		long daysAbsent = optionalLong(body, "days_absent");
		long daysLeave = optionalLong(body, "days_leave");
		BigDecimal overtimeHours = optionalDecimal(body, "overtime_hours");
		BigDecimal overtimePay = optionalDecimal(body, "overtime_pay");
		BigDecimal penaltiesTotal = optionalDecimal(body, "penalties_total");
		BigDecimal advanceDeduction = optionalDecimal(body, "advance_deduction");
		BigDecimal otherDeductions = optionalDecimal(body, "other_deductions");

		BigDecimal netSalary = basicSalary.add(allowances).add(overtimePay)
				.subtract(penaltiesTotal).subtract(advanceDeduction).subtract(otherDeductions);

		long id = store.insert(
				batchId, employeeId, daysPresent, daysAbsent, daysLeave, overtimeHours, basicSalary, allowances,
				overtimePay, penaltiesTotal, advanceDeduction, otherDeductions, netSalary);
		Map<String, Object> row = store.byId(id);
		if (row == null) {
			throw new LegacyApiException(404, "payslip_not_found");
		}
		return row;
	}

	// ------------------------------------------------------------------
	// delete.php
	// ------------------------------------------------------------------

	public void delete(long companyId, long payslipId) {
		Map<String, Object> payslip = store.withBatchStatus(payslipId, companyId);
		if (payslip == null) {
			throw new LegacyApiException(404, "payslip_not_found");
		}
		if (FINALIZED.equals(payslip.get("batch_status"))) {
			throw new LegacyApiException(400, "batch_already_finalized");
		}
		store.delete(payslipId);
	}

	// ------------------------------------------------------------------
	// one.php
	// ------------------------------------------------------------------

	public Map<String, Object> one(
			long companyId, LegacyEmployee.Role role, long authenticatedEmployeeId, long payslipId,
			String weeklyRestLabel, String officialHolidayFallbackLabel) {
		Map<String, Object> payslip = store.one(payslipId, companyId);
		if (payslip == null) {
			throw new LegacyApiException(404, "payslip_not_found");
		}
		if (role == LegacyEmployee.Role.EMPLOYEE
				&& LegacyValues.toPhpLong(payslip.get("employee_id")) != authenticatedEmployeeId) {
			throw new LegacyApiException(403, "forbidden");
		}
		return enrich(payslip, companyId, weeklyRestLabel, officialHolidayFallbackLabel);
	}

	// ------------------------------------------------------------------
	// list.php
	// ------------------------------------------------------------------

	public record Page(List<Map<String, Object>> rows, Map<String, Object> meta) {
	}

	public Page list(
			long companyId, LegacyEmployee.Role role, long authenticatedEmployeeId, LegacyQueryParameters query,
			String weeklyRestLabel, String officialHolidayFallbackLabel) {
		LegacyPagination.Params page = LegacyPagination.params(query);

		Long batchId = positiveLongOrNull(query.value("batch_id"));
		Long employeeId = positiveLongOrNull(query.value("employee_id"));
		Integer month = positiveIntOrNull(query.value("month"));
		Integer year = positiveIntOrNull(query.value("year"));
		Long branchId = positiveLongOrNull(query.value("branch_id"));
		Long departmentId = positiveLongOrNull(query.value("department_id"));
		if (role == LegacyEmployee.Role.EMPLOYEE) {
			employeeId = authenticatedEmployeeId;
		}
		boolean newEmployeesThisMonth = newThisMonth(query.value("new_employees_this_month"));
		String search = LegacyPagination.searchQueryParam(query);

		LegacyPayslipStore.Filter filter = new LegacyPayslipStore.Filter(
				companyId, batchId, employeeId, month, year, branchId, departmentId, newEmployeesThisMonth, search);

		long total = store.countForList(filter);
		List<Map<String, Object>> rows = store.list(filter, page);
		List<Map<String, Object>> enriched = rows.stream()
				.map(row -> enrich(row, companyId, weeklyRestLabel, officialHolidayFallbackLabel))
				.toList();
		return new Page(enriched, LegacyPagination.meta(total, page));
	}

	/** {@code in_array(strtolower(...), ['1','true','yes'], true)} -- narrower than {@code toPhpFilterBoolean}. */
	private static boolean newThisMonth(Object raw) {
		if (raw == null) {
			return false;
		}
		String value = LegacyValues.toPhpString(raw).toLowerCase(java.util.Locale.ROOT);
		return "1".equals(value) || "true".equals(value) || "yes".equals(value);
	}

	// ------------------------------------------------------------------
	// update.php
	// ------------------------------------------------------------------

	public Map<String, Object> update(
			long companyId, long payslipId, Map<String, Object> body,
			String weeklyRestLabel, String officialHolidayFallbackLabel) {
		Map<String, Object> payslip = store.withBatchStatus(payslipId, companyId);
		if (payslip == null) {
			throw new LegacyApiException(404, "payslip_not_found");
		}
		if (FINALIZED.equals(payslip.get("batch_status"))) {
			throw new LegacyApiException(400, "batch_already_finalized");
		}

		long employeeId = LegacyValues.toPhpLong(payslip.get("employee_id"));
		String periodFrom = LegacyValues.toPhpString(payslip.get("period_from"));
		String periodTo = LegacyValues.toPhpString(payslip.get("period_to"));

		long daysPresent = bodyLongOr(body, "days_present", payslip.get("days_present"));
		long daysAbsent = bodyLongOr(body, "days_absent", payslip.get("days_absent"));
		long daysLeave = bodyLongOr(body, "days_leave", payslip.get("days_leave"));
		BigDecimal overtimeHours = bodyDecimalOr(body, "overtime_hours", payslip.get("overtime_hours"));

		BigDecimal basicSalary = bodyDecimalOr(body, "basic_salary", payslip.get("basic_salary"));
		Object housingRaw = body.containsKey("allowances") ? body.get("allowances")
				: body.containsKey("housing_allowance") ? body.get("housing_allowance") : payslip.get("allowances");
		BigDecimal housing = housingRaw == null ? BigDecimal.ZERO : LegacyValues.toPhpDecimal(housingRaw);
		BigDecimal transport = bodyDecimalOr(body, "transport_allowance", payslip.get("transport_allowance"));
		BigDecimal food = bodyDecimalOr(body, "food_allowance", payslip.get("food_allowance"));
		BigDecimal risk = bodyDecimalOr(body, "risk_allowance", payslip.get("risk_allowance"));
		BigDecimal incentives = bodyDecimalOr(body, "incentives", payslip.get("incentives"));

		BigDecimal advanceDeduction = bodyDecimalOr(body, "advance_deduction", payslip.get("advance_deduction"));
		BigDecimal otherDeductions = bodyDecimalOr(body, "other_deductions", payslip.get("other_deductions"));
		BigDecimal insurance = bodyDecimalOr(body, "insurance_deduction", payslip.get("insurance_deduction"));
		BigDecimal tax = bodyDecimalOr(body, "tax_deduction", payslip.get("tax_deduction"));
		BigDecimal advancesContract = bodyDecimalOr(body, "advances_deduction", payslip.get("advances_deduction"));
		BigDecimal fund = bodyDecimalOr(body, "fund_deduction", payslip.get("fund_deduction"));

		BigDecimal penaltyDays = body.containsKey("penalty_days")
				? LegacyValues.toPhpDecimal(body.get("penalty_days"))
				: batchStore.unappliedPenaltyDays(employeeId, periodFrom, periodTo);

		BigDecimal gross = round(basicSalary.add(housing).add(transport).add(food).add(risk).add(incentives));
		BigDecimal dayRate = gross.signum() > 0
				? round(gross.divide(BigDecimal.valueOf(MONTH_DAYS), 10, RoundingMode.HALF_UP))
				: round(BigDecimal.ZERO);

		int voidWeeklyRestDays = 0;
		if (!periodFrom.isEmpty() && !periodTo.isEmpty() && employeeId > 0) {
			String today = clock.todayAsString();
			String asOf = today.compareTo(periodTo) <= 0 ? today : periodTo;
			LegacyPayrollAttendanceFigures.AttendanceDisplay display = attendanceFigures.attendanceDisplay(
					companyId, employeeId, periodFrom, periodTo, (int) daysPresent, asOf);
			voidWeeklyRestDays = display.voidWeeklyRestDays();
		}
		long unpaidDays = Math.max(0, daysAbsent) + Math.max(0, voidWeeklyRestDays);
		BigDecimal absenceCost = round(dayRate.multiply(BigDecimal.valueOf(unpaidDays)));
		BigDecimal salaryByAttendance = round(gross.subtract(absenceCost)).max(BigDecimal.ZERO);

		BigDecimal workHours = attendanceFigures.employeeWorkHoursPerDay(employeeId);
		BigDecimal hourly = dayRate.signum() > 0 && workHours.signum() > 0
				? dayRate.divide(workHours, 10, RoundingMode.HALF_UP)
				: BigDecimal.ZERO;

		BigDecimal overtimePay;
		if (body.containsKey("overtime_hours") || !body.containsKey("overtime_pay")) {
			BigDecimal multiplier = BigDecimal.valueOf(overtimeSettings.overtimeMultiplier(companyId));
			overtimePay = round(overtimeHours.multiply(hourly).multiply(multiplier));
		} else {
			overtimePay = LegacyValues.toPhpDecimal(body.get("overtime_pay"));
		}
		if (!overtimeSettings.companyPaysOvertime(companyId)) {
			overtimePay = round(BigDecimal.ZERO);
		}

		BigDecimal penaltiesTotal = round(dayRate.multiply(penaltyDays.max(BigDecimal.ZERO)));
		BigDecimal totalEntitlements = round(salaryByAttendance.add(overtimePay));
		BigDecimal totalDeductions = round(insurance.add(tax).add(advancesContract).add(fund)
				.add(penaltiesTotal).add(advanceDeduction).add(otherDeductions));
		BigDecimal netSalary = round(totalEntitlements.subtract(totalDeductions)).max(BigDecimal.ZERO);

		Map<String, Object> columns = new LinkedHashMap<>();
		columns.put("days_present", daysPresent);
		columns.put("days_absent", daysAbsent);
		columns.put("days_leave", daysLeave);
		columns.put("overtime_hours", overtimeHours);
		columns.put("basic_salary", basicSalary);
		columns.put("allowances", housing);
		columns.put("transport_allowance", transport);
		columns.put("food_allowance", food);
		columns.put("risk_allowance", risk);
		columns.put("incentives", incentives);
		columns.put("overtime_pay", overtimePay);
		columns.put("penalties_total", penaltiesTotal);
		columns.put("advance_deduction", advanceDeduction);
		columns.put("other_deductions", otherDeductions);
		columns.put("insurance_deduction", insurance);
		columns.put("tax_deduction", tax);
		columns.put("advances_deduction", advancesContract);
		columns.put("fund_deduction", fund);
		columns.put("gross_salary", gross);
		columns.put("total_entitlements", totalEntitlements);
		columns.put("total_deductions", totalDeductions);
		columns.put("net_salary", netSalary);
		store.update(payslipId, columns);

		Map<String, Object> row = store.byId(payslipId);
		if (row == null) {
			throw new LegacyApiException(404, "payslip_not_found");
		}
		row.put("period_from", periodFrom);
		row.put("period_to", periodTo);
		return enrich(row, companyId, weeklyRestLabel, officialHolidayFallbackLabel);
	}

	// ------------------------------------------------------------------
	// payroll_enrich_payslip_row() (payroll_calculation.php:511-651)
	// ------------------------------------------------------------------

	private Map<String, Object> enrich(
			Map<String, Object> source, long companyId, String weeklyRestLabel, String officialHolidayFallbackLabel) {
		Map<String, Object> row = new LinkedHashMap<>(source);

		String periodFrom = LegacyValues.toPhpString(firstNonNull(row.get("period_from")));
		String periodTo = LegacyValues.toPhpString(firstNonNull(row.get("period_to")));
		if ((periodFrom.isEmpty() || periodTo.isEmpty()) && row.get("batch_id") != null) {
			Map<String, Object> batch = batchStore.byId(LegacyValues.toPhpLong(row.get("batch_id")));
			if (batch != null) {
				periodFrom = LegacyValues.toPhpString(batch.get("period_from"));
				periodTo = LegacyValues.toPhpString(batch.get("period_to"));
			}
		}

		long employeeId = LegacyValues.toPhpLong(row.get("employee_id"));
		if (periodFrom.isEmpty() || periodTo.isEmpty() || employeeId <= 0 || companyId <= 0) {
			return row;
		}

		String today = clock.todayAsString();
		String asOf = today.compareTo(periodTo) <= 0 ? today : periodTo;
		boolean inProgress = asOf.compareTo(periodTo) < 0;
		String rangeTo = inProgress ? asOf : periodTo;

		LegacyPayrollAttendanceFigures.AttendanceSummary summary =
				attendanceFigures.attendanceSummary(companyId, employeeId, periodFrom, rangeTo, weeklyRestLabel);
		int punchPresent = summary.daysPresent();
		LegacyPayrollAttendanceFigures.AttendanceDisplay display = attendanceFigures.attendanceDisplay(
				companyId, employeeId, periodFrom, periodTo, punchPresent, asOf);

		int computedAbsent = display.daysAbsent();
		int voidWeeklyRestDays = display.voidWeeklyRestDays();
		int earnedWeeklyRestDays = display.earnedWeeklyRestDays();
		int officialHolidayDays = display.officialHolidayDays();
		int computedLeave = display.daysLeave();

		int daysAbsent;
		if (inProgress) {
			daysAbsent = computedAbsent;
		} else if (row.get("days_absent") != null) {
			daysAbsent = (int) LegacyValues.toPhpLong(row.get("days_absent")) + Math.max(0, voidWeeklyRestDays);
		} else {
			daysAbsent = computedAbsent;
		}

		BigDecimal contractBasic = decimal(row.get("contract_basic_salary"));
		if (contractBasic.signum() <= 0) {
			contractBasic = decimal(row.get("basic_salary"));
		}
		BigDecimal housing = decimal(row.get("allowances"));
		BigDecimal transport = decimal(row.get("transport_allowance"));
		BigDecimal food = decimal(row.get("food_allowance"));
		BigDecimal risk = decimal(row.get("risk_allowance"));
		BigDecimal incentives = decimal(row.get("incentives"));
		BigDecimal overtime = decimal(row.get("overtime_pay"));

		BigDecimal gross = decimal(row.get("gross_salary"));
		if (gross.signum() <= 0) {
			gross = round(contractBasic.add(housing).add(transport).add(food).add(risk).add(incentives));
		}

		BigDecimal dayRate = gross.signum() > 0
				? round(gross.divide(BigDecimal.valueOf(MONTH_DAYS), 10, RoundingMode.HALF_UP))
				: round(BigDecimal.ZERO);
		long unpaidDays = Math.max(0, daysAbsent);
		BigDecimal absenceCost = round(dayRate.multiply(BigDecimal.valueOf(unpaidDays)));
		BigDecimal salaryBase = attendanceSalaryBase(gross, dayRate, periodFrom, periodTo, asOf);
		BigDecimal salaryByAttendance = round(salaryBase.subtract(absenceCost)).max(BigDecimal.ZERO);

		BigDecimal penaltyDays = batchStore.unappliedPenaltyDays(employeeId, periodFrom, periodTo);

		row.put("expected_work_days", display.expectedWorkDays());
		row.put("expected_work_days_due", display.expectedWorkDaysDue());
		row.put("month_days", MONTH_DAYS);
		row.put("days_absent", daysAbsent);
		boolean hasStoredLeave = row.get("days_leave") != null;
		row.put("days_leave", hasStoredLeave && !inProgress
				? (int) LegacyValues.toPhpLong(row.get("days_leave")) : computedLeave);
		row.put("official_holiday_days", officialHolidayDays);
		row.put("earned_weekly_rest_days", earnedWeeklyRestDays);
		row.put("void_weekly_rest_days", voidWeeklyRestDays);

		int creditedWorkDays = Math.max(0, punchPresent) + Math.max(0, earnedWeeklyRestDays)
				+ Math.max(0, officialHolidayDays);
		row.put("days_present", creditedWorkDays);
		row.put("present_details", attendanceFigures.presentDetails(
				companyId, employeeId, periodFrom, periodTo, punchPresent, asOf,
				weeklyRestLabel, officialHolidayFallbackLabel));
		row.put("contract_basic_salary", round(contractBasic));
		row.put("daily_basic_rate", dayRate);
		row.put("absence_cost", absenceCost);
		row.put("salary_by_present_days", salaryByAttendance);
		row.put("salary_by_paid_leave_days", BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
		row.put("penalty_days", penaltyDays);
		row.put("gross_salary", round(gross));
		row.put("days_present_ratio", String.valueOf(creditedWorkDays));

		BigDecimal penaltiesAmount = round(dayRate.multiply(penaltyDays));
		row.put("penalties_total", penaltiesAmount);

		BigDecimal totalEntitlements = round(salaryByAttendance.add(overtime));
		BigDecimal advanceDeduction = decimal(row.get("advance_deduction"));
		BigDecimal insurance = decimal(row.get("insurance_deduction"));
		BigDecimal tax = decimal(row.get("tax_deduction"));
		BigDecimal advancesContract = decimal(row.get("advances_deduction"));
		BigDecimal fund = decimal(row.get("fund_deduction"));
		BigDecimal other = decimal(row.get("other_deductions"));
		BigDecimal totalDeductions = round(penaltiesAmount.add(advanceDeduction).add(insurance).add(tax)
				.add(advancesContract).add(fund).add(other));
		BigDecimal netSalary = round(totalEntitlements.subtract(totalDeductions)).max(BigDecimal.ZERO);

		row.put("total_entitlements", totalEntitlements);
		row.put("total_deductions", totalDeductions);
		row.put("net_salary", netSalary);

		return row;
	}

	/** {@code payroll_attendance_salary_base()} ({@code payroll_calculation.php:261-275}). */
	private BigDecimal attendanceSalaryBase(
			BigDecimal gross, BigDecimal dayRate, String periodFrom, String periodTo, String asOf) {
		if (asOf.compareTo(periodTo) >= 0) {
			return round(gross);
		}
		int elapsed = elapsedCalendarDays(periodFrom, periodTo, asOf);
		return round(dayRate.multiply(BigDecimal.valueOf(Math.max(0, elapsed))));
	}

	/** {@code payroll_elapsed_calendar_days()} ({@code payroll_calculation.php:234-255}). */
	private static int elapsedCalendarDays(String periodFrom, String periodTo, String asOf) {
		if (periodFrom.isEmpty() || periodTo.isEmpty()) {
			return 0;
		}
		String end = asOf.compareTo(periodTo) < 0 ? asOf : periodTo;
		if (end.compareTo(periodFrom) < 0) {
			return 0;
		}
		return (int) ChronoUnit.DAYS.between(LocalDate.parse(periodFrom), LocalDate.parse(end)) + 1;
	}

	private static Object firstNonNull(Object value) {
		return value == null ? "" : value;
	}

	private static BigDecimal round(BigDecimal value) {
		return value.setScale(2, RoundingMode.HALF_UP);
	}

	private static BigDecimal decimal(Object raw) {
		return raw == null ? BigDecimal.ZERO : LegacyValues.toPhpDecimal(raw);
	}

	private static long requiredLong(Map<String, Object> body, String key) {
		Object value = body.get(key);
		if (value == null || "".equals(value)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", key));
		}
		return LegacyValues.toPhpLong(value);
	}

	private static long optionalLong(Map<String, Object> body, String key) {
		Object value = body.get(key);
		return value == null ? 0 : LegacyValues.toPhpLong(value);
	}

	private static BigDecimal optionalDecimal(Map<String, Object> body, String key) {
		Object value = body.get(key);
		return value == null ? BigDecimal.ZERO : LegacyValues.toPhpDecimal(value);
	}

	private static long bodyLongOr(Map<String, Object> body, String key, Object fallback) {
		Object value = body.containsKey(key) ? body.get(key) : fallback;
		return value == null ? 0 : LegacyValues.toPhpLong(value);
	}

	private static BigDecimal bodyDecimalOr(Map<String, Object> body, String key, Object fallback) {
		Object value = body.containsKey(key) ? body.get(key) : fallback;
		return value == null ? BigDecimal.ZERO : LegacyValues.toPhpDecimal(value);
	}

	private static Long positiveLongOrNull(Object raw) {
		if (raw == null) {
			return null;
		}
		long value = LegacyValues.toPhpLong(raw);
		return value > 0 ? value : null;
	}

	private static Integer positiveIntOrNull(Object raw) {
		if (raw == null) {
			return null;
		}
		long value = LegacyValues.toPhpLong(raw);
		return value > 0 ? (int) value : null;
	}
}
