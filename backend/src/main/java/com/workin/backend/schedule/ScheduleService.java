package com.workin.backend.schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.authorization.ResourceScopeService;
import com.workin.backend.companysettings.CompanySettingsService;
import com.workin.backend.employees.EmployeeRepository;
import com.workin.backend.organization.Shift;
import com.workin.backend.organization.ShiftRepository;
import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantSessionVariable;

/**
 * Ported 1:1 from hr-legacy/apis/helpers/schedule_helper.php @ d113204.
 * Two distinct concepts, kept split exactly as legacy keeps them:
 * employee_shift_assignments = "what shift do they have going forward"
 * (append-only history); employee_schedules = "what does this specific
 * day look like" (manual rows win over computed-from-shift rows).
 * Official-holiday lookups are a declared stub (empty) until the
 * holidays module lands.
 */
@Service
public class ScheduleService {

	static final String WEEKLY_REST_LABEL = "Weekly rest";

	private final EmployeeShiftAssignmentRepository assignmentRepository;
	private final EmployeeScheduleRepository scheduleRepository;
	private final EmployeeRepository employeeRepository;
	private final ShiftRepository shiftRepository;
	private final CompanySettingsService companySettingsService;
	private final ResourceScopeService resourceScopeService;
	private final TenantSessionVariable tenantSessionVariable;

	public ScheduleService(
			EmployeeShiftAssignmentRepository assignmentRepository,
			EmployeeScheduleRepository scheduleRepository,
			EmployeeRepository employeeRepository,
			ShiftRepository shiftRepository,
			CompanySettingsService companySettingsService,
			ResourceScopeService resourceScopeService,
			TenantSessionVariable tenantSessionVariable) {
		this.assignmentRepository = assignmentRepository;
		this.scheduleRepository = scheduleRepository;
		this.employeeRepository = employeeRepository;
		this.shiftRepository = shiftRepository;
		this.companySettingsService = companySettingsService;
		this.resourceScopeService = resourceScopeService;
		this.tenantSessionVariable = tenantSessionVariable;
	}

	/** Latest assignment with effective_from <= onDate (schedule_shift_for_employee_on_date). */
	public Optional<EmployeeShiftAssignment> assignmentOnDate(Long companyId, Long employeeId, LocalDate onDate) {
		return assignmentRepository
				.findFirstByEmployeeIdAndCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDescIdDesc(
						employeeId, companyId, onDate);
	}

	/** The seam the attendance-calendar engine consumes; caller must hold a tenant transaction. */
	public Optional<Shift> shiftForEmployeeOnDate(Long companyId, Long employeeId, LocalDate onDate) {
		return assignmentOnDate(companyId, employeeId, onDate)
				.flatMap(a -> shiftRepository.findByIdAndCompanyId(a.getShiftId(), companyId));
	}

	/** schedule_is_weekly_rest_day: shift days_off OR company weekly_off_days marks the dow. */
	public boolean isWeeklyRestDay(Long companyId, Shift shift, LocalDate date) {
		DayOfWeek dow = date.getDayOfWeek();
		return DaysOffParser.parseDaysOff(shift == null ? null : shift.getDaysOff()).contains(dow)
				|| companyRestDays(companyId).contains(dow);
	}

	@Transactional(readOnly = true)
	public Optional<MonthlyOverviewView> monthlyOverview(
			AuthorizationContext context, Long employeeId, int year, int month) {
		tenantSessionVariable.apply(context.companyId());
		if (!employeeInScope(context, employeeId)) {
			return Optional.empty();
		}
		int clamped = Math.max(1, Math.min(12, month));
		LocalDate from = LocalDate.of(year, clamped, 1);
		LocalDate to = from.withDayOfMonth(from.lengthOfMonth());
		LocalDate today = LocalDate.now();
		LocalDate summaryDate = (today.getYear() == year && today.getMonthValue() == clamped) ? today : to;

		Optional<EmployeeShiftAssignment> current = assignmentOnDate(context.companyId(), employeeId, summaryDate);
		ShiftSummaryView summary = current.map(a -> shiftSummary(context.companyId(), a)).orElse(null);
		List<WeeklyRestDayView> weeklyRest = current
				.flatMap(a -> shiftRepository.findByIdAndCompanyId(a.getShiftId(), context.companyId()))
				.map(shift -> weeklyRestDays(context.companyId(), shift))
				.orElse(List.of());

		return Optional.of(new MonthlyOverviewView(
				summary, weeklyRest, List.of(), computeDays(context.companyId(), employeeId, from, to)));
	}

	/** schedule_shift_summary: effective_to = day before the next assignment, or open-ended. */
	private ShiftSummaryView shiftSummary(Long companyId, EmployeeShiftAssignment assignment) {
		Shift shift = shiftRepository.findByIdAndCompanyId(assignment.getShiftId(), companyId).orElse(null);
		LocalDate effectiveTo = assignmentRepository
				.findFirstByEmployeeIdAndCompanyIdAndEffectiveFromGreaterThanOrderByEffectiveFromAscIdAsc(
						assignment.getEmployeeId(), companyId, assignment.getEffectiveFrom())
				.map(next -> next.getEffectiveFrom().minusDays(1))
				.orElse(null);
		return new ShiftSummaryView(
				assignment.getShiftId(),
				shift != null ? shift.getName() : "",
				shift != null ? shift.getStartTime() : null,
				shift != null ? shift.getEndTime() : null,
				assignment.getEffectiveFrom(),
				effectiveTo);
	}

	/** schedule_collect_weekly_rest_days: shift ∪ company, ascending legacy dow order. */
	private List<WeeklyRestDayView> weeklyRestDays(Long companyId, Shift shift) {
		Set<DayOfWeek> union = DaysOffParser.parseDaysOff(shift.getDaysOff());
		union.addAll(companyRestDays(companyId));
		return union.stream()
				.sorted(Comparator.comparingInt(DaysOffParser::toLegacyIndex))
				.map(d -> new WeeklyRestDayView(DaysOffParser.toLegacyIndex(d), DaysOffParser.englishLabel(d)))
				.toList();
	}

	private Set<DayOfWeek> companyRestDays(Long companyId) {
		return DaysOffParser.parseCompanyRestDays(
				companySettingsService.effective(companyId).weeklyOffDays());
	}

	/** schedule_compute_days_for_range: read-only, manual rows win, days with no shift are skipped. */
	private List<ScheduleDayView> computeDays(Long companyId, Long employeeId, LocalDate from, LocalDate to) {
		if (to.isBefore(from)) {
			return List.of();
		}
		Map<LocalDate, EmployeeSchedule> manual = scheduleRepository
				.findByEmployeeIdAndCompanyIdAndScheduleDateBetweenOrderByScheduleDateAsc(
						employeeId, companyId, from, to)
				.stream()
				.collect(Collectors.toMap(EmployeeSchedule::getScheduleDate, Function.identity()));
		Set<DayOfWeek> companyRest = companyRestDays(companyId);
		List<ScheduleDayView> out = new ArrayList<>();
		for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
			EmployeeSchedule row = manual.get(d);
			if (row != null) {
				out.add(new ScheduleDayView(row.getId(), d, row.getName(),
						row.getStartTime(), row.getEndTime(), row.getExceptionNote()));
				continue;
			}
			Optional<Shift> dayShift = shiftForEmployeeOnDate(companyId, employeeId, d);
			if (dayShift.isEmpty()) {
				continue;
			}
			Shift shift = dayShift.get();
			boolean rest = DaysOffParser.parseDaysOff(shift.getDaysOff()).contains(d.getDayOfWeek())
					|| companyRest.contains(d.getDayOfWeek());
			if (rest) {
				// schedule_row_from_shift: an exception label suppresses the shift columns.
				out.add(new ScheduleDayView(null, d, null, null, null, WEEKLY_REST_LABEL));
			} else {
				out.add(new ScheduleDayView(null, d, blankToNull(shift.getName()),
						shift.getStartTime(), shift.getEndTime(), null));
			}
		}
		return out;
	}

	private boolean employeeInScope(AuthorizationContext context, Long employeeId) {
		return employeeRepository.findByIdAndCompanyId(employeeId, context.companyId())
				.filter(e -> resourceScopeService.isEmployeeInScope(context, e.getId()))
				.isPresent();
	}

	private static String blankToNull(String value) {
		return value == null || value.trim().isEmpty() ? null : value.trim();
	}

}
