package com.workin.backend.attendance;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.authorization.ResourceScopeService;
import com.workin.backend.employees.EmployeeRepository;
import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantSessionVariable;

/**
 * HR manual-entry surface (module template) carrying the module's two
 * documented legacy rules (docs/legacy/business-rule-extraction.md):
 *
 * <p><b>Punches XOR exception day.</b> Shape is resolved by
 * {@code exceptionTypeId}: set -> exception day ({@code date}
 * required, all punch fields forbidden; stored as UTC-midnight
 * check-in, null checkout); unset -> punch ({@code checkIn} and
 * {@code method} required, {@code date} forbidden). Mixed/incomplete
 * shapes are 400s -- including an update clearing both punches
 * without an exception, which legacy's update.php answered by
 * silently deleting the row (deliberate non-port, owner-confirmed:
 * the new surface has a real DELETE).
 *
 * <p><b>2-hour minimum gap</b>, punches only: a punch landing 0-119
 * minutes after the employee's latest earlier punch is a conflict.
 * Legacy enforces this same guard on HR manual entry (create.php
 * lines 67-73), and its formula ({@code minutes_since_last >= 0 &&
 * < 120}) only fires in the after-direction -- backdated entries
 * more than 120 minutes before an existing punch pass. Exception
 * rows are excluded from the guard (recorded slice decision: it is
 * an anti-double-punch control; legacy's depth here is undocumented).
 */
@Service
public class AttendanceService {

	private static final Duration MINIMUM_GAP = Duration.ofMinutes(120);
	private static final long NO_EXCLUDED_ID = -1L;

	private final AttendanceRepository attendanceRepository;
	private final ExceptionTypeRepository exceptionTypeRepository;
	private final EmployeeRepository employeeRepository;
	private final ResourceScopeService resourceScopeService;
	private final TenantSessionVariable tenantSessionVariable;

	public AttendanceService(
			AttendanceRepository attendanceRepository,
			ExceptionTypeRepository exceptionTypeRepository,
			EmployeeRepository employeeRepository,
			ResourceScopeService resourceScopeService,
			TenantSessionVariable tenantSessionVariable) {
		this.attendanceRepository = attendanceRepository;
		this.exceptionTypeRepository = exceptionTypeRepository;
		this.employeeRepository = employeeRepository;
		this.resourceScopeService = resourceScopeService;
		this.tenantSessionVariable = tenantSessionVariable;
	}

	@Transactional(readOnly = true)
	public List<AttendanceView> list(AuthorizationContext context, Long employeeId, LocalDate from, LocalDate to) {
		tenantSessionVariable.apply(context.companyId());
		Set<Long> reach = scopedReachOrNull(context);
		List<Attendance> rows = employeeId != null
				? attendanceRepository.findByEmployeeIdAndCompanyIdOrderById(employeeId, context.companyId())
				: attendanceRepository.findByCompanyIdOrderById(context.companyId());
		Instant lowerBound = from != null ? from.atStartOfDay(ZoneOffset.UTC).toInstant() : null;
		Instant upperBound = to != null ? to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant() : null;
		return rows.stream()
				.filter(row -> reach == null || reach.contains(row.getEmployeeId()))
				.filter(row -> lowerBound == null || !row.getCheckIn().isBefore(lowerBound))
				.filter(row -> upperBound == null || row.getCheckIn().isBefore(upperBound))
				.map(AttendanceView::of)
				.toList();
	}

	@Transactional(readOnly = true)
	public Optional<AttendanceView> get(AuthorizationContext context, Long attendanceId) {
		tenantSessionVariable.apply(context.companyId());
		return attendanceRepository.findByIdAndCompanyId(attendanceId, context.companyId())
				.filter(row -> canReach(context, row.getEmployeeId()))
				.map(AttendanceView::of);
	}

	@Transactional
	public MutationResult create(AuthorizationContext context, CreateAttendanceRequest request) {
		tenantSessionVariable.apply(context.companyId());
		if (employeeRepository.findByIdAndCompanyId(request.employeeId(), context.companyId()).isEmpty()
				|| !canReach(context, request.employeeId())) {
			return new MutationResult.NotFound();
		}
		Attendance attendance = new Attendance(request.employeeId(), context.companyId());
		MutationResult applied = applyShape(context, attendance, NO_EXCLUDED_ID,
				request.checkIn(), request.checkOut(), request.method(), request.latitude(), request.longitude(),
				request.date(), request.exceptionTypeId());
		if (applied != null) {
			return applied;
		}
		return new MutationResult.Done(AttendanceView.of(attendanceRepository.save(attendance)));
	}

	@Transactional
	public MutationResult update(AuthorizationContext context, Long attendanceId, UpdateAttendanceRequest request) {
		tenantSessionVariable.apply(context.companyId());
		Optional<Attendance> existing = attendanceRepository.findByIdAndCompanyId(attendanceId, context.companyId());
		if (existing.isEmpty() || !canReach(context, existing.get().getEmployeeId())) {
			return new MutationResult.NotFound();
		}
		Attendance attendance = existing.get();
		MutationResult applied = applyShape(context, attendance, attendance.getId(),
				request.checkIn(), request.checkOut(), request.method(), request.latitude(), request.longitude(),
				request.date(), request.exceptionTypeId());
		if (applied != null) {
			return applied;
		}
		return new MutationResult.Done(AttendanceView.of(attendance));
	}

	@Transactional
	public MutationResult delete(AuthorizationContext context, Long attendanceId) {
		tenantSessionVariable.apply(context.companyId());
		Optional<Attendance> existing = attendanceRepository.findByIdAndCompanyId(attendanceId, context.companyId());
		if (existing.isEmpty() || !canReach(context, existing.get().getEmployeeId())) {
			return new MutationResult.NotFound();
		}
		attendanceRepository.delete(existing.get());
		return new MutationResult.Done(null);
	}

	/**
	 * Enforcement boundary 3: {@code null} reach = not scope-limited
	 * (company-wide, everyone reachable). Otherwise the row's employee
	 * must be in the scoped set. Computed once per call.
	 */
	private Set<Long> scopedReachOrNull(AuthorizationContext context) {
		return resourceScopeService.isScopeLimited(context)
				? resourceScopeService.reachableEmployeeIds(context)
				: null;
	}

	private boolean canReach(AuthorizationContext context, Long employeeId) {
		if (!resourceScopeService.isScopeLimited(context)) {
			return true;
		}
		return resourceScopeService.canReachEmployee(context, employeeId);
	}

	/**
	 * Resolves and applies the request's shape onto the row. Returns
	 * null on success, or the failure result to bubble up.
	 */
	private MutationResult applyShape(AuthorizationContext context, Attendance attendance, long excludedId,
			Instant checkIn, Instant checkOut, String method, BigDecimal latitude, BigDecimal longitude,
			LocalDate date, Long exceptionTypeId) {
		if (exceptionTypeId != null) {
			if (date == null) {
				return new MutationResult.InvalidShape("exception shape requires date");
			}
			if (checkIn != null || checkOut != null || method != null || latitude != null || longitude != null) {
				return new MutationResult.InvalidShape("exception shape forbids punch fields");
			}
			if (exceptionTypeRepository.findByIdAndCompanyId(exceptionTypeId, context.companyId()).isEmpty()) {
				return new MutationResult.NotFound();
			}
			attendance.applyException(date.atStartOfDay(ZoneOffset.UTC).toInstant(), exceptionTypeId);
			return null;
		}
		if (checkIn == null || method == null) {
			return new MutationResult.InvalidShape("punch shape requires checkIn and method");
		}
		if (date != null) {
			return new MutationResult.InvalidShape("punch shape forbids date");
		}
		Optional<Attendance> latestEarlierPunch = attendanceRepository
				.findFirstByEmployeeIdAndCompanyIdAndExceptionTypeIdIsNullAndIdNotAndCheckInLessThanEqualOrderByCheckInDesc(
						attendance.getEmployeeId(), context.companyId(), excludedId, checkIn);
		if (latestEarlierPunch.isPresent()
				&& Duration.between(latestEarlierPunch.get().getCheckIn(), checkIn).compareTo(MINIMUM_GAP) < 0) {
			return new MutationResult.GapViolation();
		}
		attendance.applyPunch(checkIn, checkOut, method, latitude, longitude);
		return null;
	}

	public sealed interface MutationResult {

		record NotFound() implements MutationResult {
		}

		record InvalidShape(String reason) implements MutationResult {
		}

		/** The 2-hour minimum-gap rule (hr-legacy check_in.php lines 33-42, create.php 67-73). */
		record GapViolation() implements MutationResult {
		}

		record Done(AttendanceView view) implements MutationResult {
		}

	}

}
