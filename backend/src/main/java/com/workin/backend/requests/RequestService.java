package com.workin.backend.requests;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.attendance.Attendance;
import com.workin.backend.attendance.AttendanceRepository;
import com.workin.backend.employees.EmployeeRepository;
import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantSessionVariable;

/**
 * The leave/permission request workflow. Approval semantics are
 * hr-legacy's request_actions_helper.php, read in full at the pinned
 * Discovery commit and ported exactly (slice spec records each
 * deliberate divergence):
 *
 * <ul>
 *   <li>Inclusive day count ({@code to - from + 1}, min 1), with every
 *   day attributed to the from-date's year -- the multi-year quirk is
 *   ported, not fixed.</li>
 *   <li>Insufficient balance is a 422 only when a balance row exists;
 *   a missing row passes the check and the side effect auto-creates
 *   it at the 21.0-day fallback (legacy's MONTHLY_LEAVE_ACCRUAL
 *   company-setting default; the settings module does not exist yet,
 *   so the constant is the whole story -- recorded decision),
 *   possibly into negative remaining.</li>
 *   <li>Attendance exceptions: one row per calendar day in the range,
 *   skipping days that already hold any attendance row; rows use the
 *   new attendance convention (UTC-midnight check-in, null
 *   checkout/method), not legacy's 09:00/'app' quirk. A type with the
 *   flag but no exception_type_id skips the side effect (legacy's
 *   company-default fallback resolver is an open question).</li>
 * </ul>
 */
@Service
public class RequestService {

	private static final BigDecimal FALLBACK_TOTAL_DAYS = new BigDecimal("21.0");

	private final LeaveRequestRepository leaveRequestRepository;
	private final RequestTypeRepository requestTypeRepository;
	private final LeaveBalanceRepository leaveBalanceRepository;
	private final AttendanceRepository attendanceRepository;
	private final EmployeeRepository employeeRepository;
	private final TenantSessionVariable tenantSessionVariable;

	public RequestService(
			LeaveRequestRepository leaveRequestRepository,
			RequestTypeRepository requestTypeRepository,
			LeaveBalanceRepository leaveBalanceRepository,
			AttendanceRepository attendanceRepository,
			EmployeeRepository employeeRepository,
			TenantSessionVariable tenantSessionVariable) {
		this.leaveRequestRepository = leaveRequestRepository;
		this.requestTypeRepository = requestTypeRepository;
		this.leaveBalanceRepository = leaveBalanceRepository;
		this.attendanceRepository = attendanceRepository;
		this.employeeRepository = employeeRepository;
		this.tenantSessionVariable = tenantSessionVariable;
	}

	@Transactional(readOnly = true)
	public List<RequestView> list(AuthorizationContext context, Long employeeId, RequestStatus status) {
		tenantSessionVariable.apply(context.companyId());
		List<LeaveRequest> rows = employeeId != null
				? leaveRequestRepository.findByEmployeeIdAndCompanyIdOrderById(employeeId, context.companyId())
				: leaveRequestRepository.findByCompanyIdOrderById(context.companyId());
		return rows.stream()
				.filter(row -> status == null || row.getStatus() == status)
				.map(RequestView::of)
				.toList();
	}

	@Transactional(readOnly = true)
	public Optional<RequestView> get(AuthorizationContext context, Long requestId) {
		tenantSessionVariable.apply(context.companyId());
		return leaveRequestRepository.findByIdAndCompanyId(requestId, context.companyId()).map(RequestView::of);
	}

	@Transactional
	public MutationResult create(AuthorizationContext context, CreateRequestRequest request) {
		tenantSessionVariable.apply(context.companyId());
		if (employeeRepository.findByIdAndCompanyId(request.employeeId(), context.companyId()).isEmpty()
				|| requestTypeRepository.findByIdAndCompanyId(request.requestTypeId(), context.companyId()).isEmpty()) {
			return new MutationResult.NotFound();
		}
		LeaveRequest created = new LeaveRequest(
				request.employeeId(), context.companyId(), request.requestTypeId(),
				request.fromDate(), request.toDate(), request.fromTime(), request.toTime(), request.notes());
		return new MutationResult.Done(RequestView.of(leaveRequestRepository.save(created)));
	}

	@Transactional
	public MutationResult update(AuthorizationContext context, Long requestId, UpdateRequestRequest request) {
		tenantSessionVariable.apply(context.companyId());
		Optional<LeaveRequest> found = leaveRequestRepository.findByIdAndCompanyId(requestId, context.companyId());
		if (found.isEmpty()) {
			return new MutationResult.NotFound();
		}
		if (found.get().getStatus() != RequestStatus.PENDING) {
			return new MutationResult.WrongState();
		}
		if (requestTypeRepository.findByIdAndCompanyId(request.requestTypeId(), context.companyId()).isEmpty()) {
			return new MutationResult.NotFound();
		}
		found.get().updateDetails(request.requestTypeId(), request.fromDate(), request.toDate(),
				request.fromTime(), request.toTime(), request.notes());
		return new MutationResult.Done(RequestView.of(found.get()));
	}

	@Transactional
	public MutationResult delete(AuthorizationContext context, Long requestId) {
		tenantSessionVariable.apply(context.companyId());
		Optional<LeaveRequest> found = leaveRequestRepository.findByIdAndCompanyId(requestId, context.companyId());
		if (found.isEmpty()) {
			return new MutationResult.NotFound();
		}
		if (found.get().getStatus() != RequestStatus.PENDING) {
			return new MutationResult.WrongState();
		}
		leaveRequestRepository.delete(found.get());
		return new MutationResult.Done(null);
	}

	@Transactional
	public MutationResult approve(AuthorizationContext context, Long requestId, String reply) {
		tenantSessionVariable.apply(context.companyId());
		Optional<LeaveRequest> found = leaveRequestRepository.findByIdAndCompanyId(requestId, context.companyId());
		if (found.isEmpty()) {
			return new MutationResult.NotFound();
		}
		LeaveRequest request = found.get();
		if (request.getStatus() != RequestStatus.PENDING) {
			return new MutationResult.WrongState();
		}
		RequestType type = requestTypeRepository.findByIdAndCompanyId(request.getRequestTypeId(), context.companyId())
				.orElseThrow();

		BigDecimal days = BigDecimal.valueOf(inclusiveDayCount(request.getFromDate(), request.getToDate()));
		Short year = (short) request.getFromDate().getYear();

		if (type.isDeductBalance()) {
			Optional<LeaveBalance> balance = leaveBalanceRepository.findByEmployeeIdAndYear(request.getEmployeeId(), year);
			// Legacy quirk, ported: only an EXISTING row can be
			// insufficient; a missing row passes and is auto-created below.
			if (balance.isPresent()
					&& balance.get().getTotalDays().subtract(balance.get().getUsedDays()).compareTo(days) < 0) {
				return new MutationResult.InsufficientBalance();
			}
		}

		request.decide(RequestStatus.APPROVED, normalizeReply(reply), context.membershipId(), Instant.now());

		if (type.isDeductBalance()) {
			applyLeaveDeduction(request.getEmployeeId(), context.companyId(), days, year);
		}
		if (type.isAddAttendanceException() && type.getExceptionTypeId() != null) {
			applyAttendanceExceptions(request.getEmployeeId(), context.companyId(),
					request.getFromDate(), request.getToDate(), type.getExceptionTypeId());
		}
		return new MutationResult.Done(RequestView.of(request));
	}

	@Transactional
	public MutationResult reject(AuthorizationContext context, Long requestId, String reply) {
		tenantSessionVariable.apply(context.companyId());
		Optional<LeaveRequest> found = leaveRequestRepository.findByIdAndCompanyId(requestId, context.companyId());
		if (found.isEmpty()) {
			return new MutationResult.NotFound();
		}
		if (found.get().getStatus() != RequestStatus.PENDING) {
			return new MutationResult.WrongState();
		}
		found.get().decide(RequestStatus.REJECTED, normalizeReply(reply), context.membershipId(), Instant.now());
		return new MutationResult.Done(RequestView.of(found.get()));
	}

	/** Legacy request_inclusive_day_count: to - from + 1, min 1. */
	private static long inclusiveDayCount(LocalDate from, LocalDate to) {
		if (to.isBefore(from)) {
			return 1;
		}
		return ChronoUnit.DAYS.between(from, to) + 1;
	}

	private static String normalizeReply(String reply) {
		return reply == null || reply.isBlank() ? null : reply;
	}

	private void applyLeaveDeduction(Long employeeId, Long companyId, BigDecimal days, Short year) {
		Optional<LeaveBalance> existing = leaveBalanceRepository.findByEmployeeIdAndYear(employeeId, year);
		if (existing.isPresent()) {
			existing.get().addUsedDays(days);
			return;
		}
		LeaveBalance created = new LeaveBalance(employeeId, companyId, year);
		created.applySettings(FALLBACK_TOTAL_DAYS, null, null, null);
		created.addUsedDays(days);
		leaveBalanceRepository.save(created);
	}

	private void applyAttendanceExceptions(Long employeeId, Long companyId,
			LocalDate from, LocalDate to, Long exceptionTypeId) {
		for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
			Instant dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant();
			if (attendanceRepository.existsByEmployeeIdAndCheckInGreaterThanEqualAndCheckInLessThan(
					employeeId, dayStart, dayStart.plus(1, ChronoUnit.DAYS))) {
				continue;
			}
			Attendance exception = new Attendance(employeeId, companyId);
			exception.applyException(dayStart, exceptionTypeId);
			attendanceRepository.save(exception);
		}
	}

	public sealed interface MutationResult {

		record NotFound() implements MutationResult {
		}

		/** Request already decided -- pending-only surface (legacy ALREADY_DECIDED). */
		record WrongState() implements MutationResult {
		}

		/** Legacy INSUFFICIENT_LEAVE_BALANCE, 422. */
		record InsufficientBalance() implements MutationResult {
		}

		record Done(RequestView view) implements MutationResult {
		}

	}

}
