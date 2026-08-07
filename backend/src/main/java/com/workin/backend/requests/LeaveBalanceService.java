package com.workin.backend.requests;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.workin.backend.employees.EmployeeRepository;
import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantSessionVariable;

/**
 * Leave-balances application service, module template. Duplicate
 * (employee, year) is a real DB constraint (V25) -- a second create
 * is a clean conflict, no app-level pre-check race. used_days is not
 * writable here; it belongs to {@link RequestService}'s approval side
 * effect.
 */
@Service
public class LeaveBalanceService {

	private final LeaveBalanceRepository leaveBalanceRepository;
	private final EmployeeRepository employeeRepository;
	private final TenantSessionVariable tenantSessionVariable;

	public LeaveBalanceService(
			LeaveBalanceRepository leaveBalanceRepository,
			EmployeeRepository employeeRepository,
			TenantSessionVariable tenantSessionVariable) {
		this.leaveBalanceRepository = leaveBalanceRepository;
		this.employeeRepository = employeeRepository;
		this.tenantSessionVariable = tenantSessionVariable;
	}

	@Transactional(readOnly = true)
	public List<LeaveBalanceView> list(AuthorizationContext context, Long employeeId, Short year) {
		tenantSessionVariable.apply(context.companyId());
		List<LeaveBalance> rows = employeeId != null
				? leaveBalanceRepository.findByEmployeeIdAndCompanyIdOrderById(employeeId, context.companyId())
				: leaveBalanceRepository.findByCompanyIdOrderById(context.companyId());
		return rows.stream()
				.filter(row -> year == null || row.getYear().equals(year))
				.map(LeaveBalanceView::of)
				.toList();
	}

	@Transactional(readOnly = true)
	public Optional<LeaveBalanceView> get(AuthorizationContext context, Long balanceId) {
		tenantSessionVariable.apply(context.companyId());
		return leaveBalanceRepository.findByIdAndCompanyId(balanceId, context.companyId()).map(LeaveBalanceView::of);
	}

	@Transactional
	public MutationResult create(AuthorizationContext context, CreateLeaveBalanceRequest request) {
		tenantSessionVariable.apply(context.companyId());
		if (employeeRepository.findByIdAndCompanyId(request.employeeId(), context.companyId()).isEmpty()) {
			return new MutationResult.NotFound();
		}
		LeaveBalance balance = new LeaveBalance(request.employeeId(), context.companyId(), request.year());
		balance.applySettings(request.totalDays(), request.periodFromMonth(), request.periodToMonth(),
				request.monthlyCapDays());
		try {
			return new MutationResult.Done(LeaveBalanceView.of(leaveBalanceRepository.saveAndFlush(balance)));
		} catch (DataIntegrityViolationException ex) {
			// Thrown, not returned: the failed INSERT has already marked
			// the transaction rollback-only, so returning normally would
			// die at commit with UnexpectedRollbackException (the
			// PayrollBatchService.create precedent).
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"A leave balance for this employee and year already exists", ex);
		}
	}

	@Transactional
	public MutationResult update(AuthorizationContext context, Long balanceId, UpdateLeaveBalanceRequest request) {
		tenantSessionVariable.apply(context.companyId());
		Optional<LeaveBalance> found = leaveBalanceRepository.findByIdAndCompanyId(balanceId, context.companyId());
		if (found.isEmpty()) {
			return new MutationResult.NotFound();
		}
		LeaveBalance balance = found.get();
		balance.applySettings(request.totalDays(), request.periodFromMonth(), request.periodToMonth(),
				request.monthlyCapDays());
		leaveBalanceRepository.flush();
		return new MutationResult.Done(LeaveBalanceView.of(balance));
	}

	public sealed interface MutationResult {

		record NotFound() implements MutationResult {
		}

		record Done(LeaveBalanceView view) implements MutationResult {
		}

	}

}
