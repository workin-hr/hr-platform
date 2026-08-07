package com.workin.backend.payroll;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
 * Class-level {@code @Transactional} -- see {@code AdvanceController}'s
 * Javadoc for why resolving the tenant context and the subsequent
 * service call must share one transaction.
 */
@RestController
@Transactional
public class PayrollBatchController {

	public record CreateBatchRequest(@NotNull @Min(1) @Max(12) Short month, @NotNull Short year) {
	}

	public record UpdateBatchPeriodRequest(@NotNull LocalDate periodFrom, @NotNull LocalDate periodTo) {
	}

	public record PayrollBatchResponse(
			Long id, Short month, Short year, LocalDate periodFrom, LocalDate periodTo, BatchStatus status) {

		static PayrollBatchResponse from(PayrollBatch b) {
			return new PayrollBatchResponse(b.getId(), b.getMonth(), b.getYear(), b.getPeriodFrom(), b.getPeriodTo(), b.getStatus());
		}
	}

	private final PayrollBatchService payrollBatchService;
	private final AuthorizationContextResolver authorizationContextResolver;

	public PayrollBatchController(
			PayrollBatchService payrollBatchService, AuthorizationContextResolver authorizationContextResolver) {
		this.payrollBatchService = payrollBatchService;
		this.authorizationContextResolver = authorizationContextResolver;
	}

	@PostMapping("/api/payroll/batches")
	public ResponseEntity<PayrollBatchResponse> create(@Valid @RequestBody CreateBatchRequest request) {
		AuthorizationContext context = authorizationContextResolver.resolve();
		PayrollBatch created = payrollBatchService.create(context, request.month(), request.year());
		return ResponseEntity.status(HttpStatus.CREATED).body(PayrollBatchResponse.from(created));
	}

	@PostMapping("/api/payroll/batches/{id}/calculate")
	public PayrollBatchResponse calculate(@PathVariable Long id) {
		AuthorizationContext context = authorizationContextResolver.resolve();
		return PayrollBatchResponse.from(payrollBatchService.calculate(context, id));
	}

	@PutMapping("/api/payroll/batches/{id}/finalize")
	public PayrollBatchResponse finalizeBatch(@PathVariable Long id) {
		AuthorizationContext context = authorizationContextResolver.resolve();
		return PayrollBatchResponse.from(payrollBatchService.finalizeBatch(context, id));
	}

	@PutMapping("/api/payroll/batches/{id}/reopen")
	public PayrollBatchResponse reopen(@PathVariable Long id) {
		AuthorizationContext context = authorizationContextResolver.resolve();
		return PayrollBatchResponse.from(payrollBatchService.reopen(context, id));
	}

	@DeleteMapping("/api/payroll/batches/{id}")
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		AuthorizationContext context = authorizationContextResolver.resolve();
		payrollBatchService.delete(context, id);
		return ResponseEntity.noContent().build();
	}

	@PutMapping("/api/payroll/batches/{id}")
	public PayrollBatchResponse update(@PathVariable Long id, @Valid @RequestBody UpdateBatchPeriodRequest request) {
		AuthorizationContext context = authorizationContextResolver.resolve();
		return PayrollBatchResponse.from(payrollBatchService.updatePeriod(context, id, request.periodFrom(), request.periodTo()));
	}

	@GetMapping("/api/payroll/batches/{id}")
	public PayrollBatchResponse one(@PathVariable Long id) {
		AuthorizationContext context = authorizationContextResolver.resolve();
		return PayrollBatchResponse.from(payrollBatchService.findOne(context, id));
	}

	@GetMapping("/api/payroll/batches")
	public List<PayrollBatchResponse> list() {
		AuthorizationContext context = authorizationContextResolver.resolve();
		return payrollBatchService.list(context).stream().map(PayrollBatchResponse::from).toList();
	}

}
