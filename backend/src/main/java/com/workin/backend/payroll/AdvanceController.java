package com.workin.backend.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.AuthorizationContextResolver;

/**
 * Class-level {@code @Transactional}: {@link AuthorizationContextResolver#resolve}
 * (which calls the separately-@Transactional
 * {@code TenantContextService.establishContext}, setting the
 * {@code app.current_company_id} session variable via {@code SET
 * LOCAL}) and the subsequent service call must share one transaction --
 * "local" settings are cleared the instant their transaction commits.
 * Without this, resolve() commits and releases its connection before
 * the service call opens a new one, silently losing the RLS scoping on
 * a pooled connection. TenantController's own single demonstration
 * endpoint never needed this because it makes no further RLS-scoped
 * query after resolving context.
 */
@RestController
@Transactional
public class AdvanceController {

	public record CreateAdvanceRequest(
			Long employeeId,
			@NotNull BigDecimal amount,
			String reason,
			DeductionMode deductionMode,
			int deductionMonthCount,
			BigDecimal deductionAmountPerMonth,
			Short deductionPayrollYear,
			Short deductionPayrollMonth,
			AdvanceStatus status) {

		AdvanceService.CreateFields toFields() {
			return new AdvanceService.CreateFields(
					employeeId, amount, reason, deductionMode, deductionMonthCount, deductionAmountPerMonth,
					deductionPayrollYear, deductionPayrollMonth, status);
		}
	}

	public record UpdateAdvanceRequest(
			BigDecimal amount,
			String reason,
			DeductionMode deductionMode,
			int deductionMonthCount,
			BigDecimal deductionAmountPerMonth,
			Short deductionPayrollYear,
			Short deductionPayrollMonth,
			AdvanceStatus status) {

		AdvanceService.UpdateFields toFields() {
			return new AdvanceService.UpdateFields(
					amount, reason, deductionMode, deductionMonthCount, deductionAmountPerMonth,
					deductionPayrollYear, deductionPayrollMonth, status);
		}
	}

	public record RejectAdvanceRequest(@NotNull String rejectionReason) {
	}

	public record PayAdvanceRequest(@NotNull BigDecimal amount) {
	}

	public record AdvanceResponse(
			Long id,
			Long employeeId,
			BigDecimal amount,
			BigDecimal remaining,
			String reason,
			DeductionMode deductionMode,
			AdvanceStatus status,
			String rejectionReason,
			LocalDate requestDate) {

		static AdvanceResponse from(Advance a) {
			return new AdvanceResponse(
					a.getId(), a.getEmployeeId(), a.getAmount(), a.getRemaining(), a.getReason(),
					a.getDeductionMode(), a.getStatus(), a.getRejectionReason(), a.getRequestDate());
		}
	}

	private final AdvanceService advanceService;
	private final AuthorizationContextResolver authorizationContextResolver;

	public AdvanceController(AdvanceService advanceService, AuthorizationContextResolver authorizationContextResolver) {
		this.advanceService = advanceService;
		this.authorizationContextResolver = authorizationContextResolver;
	}

	@PostMapping("/api/payroll/advances")
	public ResponseEntity<AdvanceResponse> create(@Valid @RequestBody CreateAdvanceRequest request) {
		AuthorizationContext context = authorizationContextResolver.resolve();
		Advance created = advanceService.create(context, request.toFields());
		return ResponseEntity.status(HttpStatus.CREATED).body(AdvanceResponse.from(created));
	}

	@PutMapping("/api/payroll/advances/{id}/approve")
	public AdvanceResponse approve(@PathVariable Long id) {
		AuthorizationContext context = authorizationContextResolver.resolve();
		return AdvanceResponse.from(advanceService.approve(context, id));
	}

	@PutMapping("/api/payroll/advances/{id}/reject")
	public AdvanceResponse reject(@PathVariable Long id, @Valid @RequestBody RejectAdvanceRequest request) {
		AuthorizationContext context = authorizationContextResolver.resolve();
		return AdvanceResponse.from(advanceService.reject(context, id, request.rejectionReason()));
	}

	@PutMapping("/api/payroll/advances/{id}/pay")
	public AdvanceResponse pay(@PathVariable Long id, @Valid @RequestBody PayAdvanceRequest request) {
		AuthorizationContext context = authorizationContextResolver.resolve();
		return AdvanceResponse.from(advanceService.pay(context, id, request.amount()));
	}

	@DeleteMapping("/api/payroll/advances/{id}")
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		AuthorizationContext context = authorizationContextResolver.resolve();
		advanceService.delete(context, id);
		return ResponseEntity.noContent().build();
	}

	@PutMapping("/api/payroll/advances/{id}")
	public AdvanceResponse update(@PathVariable Long id, @Valid @RequestBody UpdateAdvanceRequest request) {
		AuthorizationContext context = authorizationContextResolver.resolve();
		return AdvanceResponse.from(advanceService.update(context, id, request.toFields()));
	}

	@GetMapping("/api/payroll/advances/{id}")
	public AdvanceResponse one(@PathVariable Long id) {
		AuthorizationContext context = authorizationContextResolver.resolve();
		return AdvanceResponse.from(advanceService.findOne(context, id));
	}

	@GetMapping("/api/payroll/advances")
	public List<AdvanceResponse> list() {
		AuthorizationContext context = authorizationContextResolver.resolve();
		return advanceService.list(context).stream().map(AdvanceResponse::from).toList();
	}

}
