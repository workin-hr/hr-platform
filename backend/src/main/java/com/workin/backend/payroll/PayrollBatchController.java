package com.workin.backend.payroll;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.workin.backend.authorization.PermissionKeys;
import com.workin.backend.authorization.RequiresPermission;
import com.workin.backend.payroll.PayrollBatchService.MutationResult;
import com.workin.backend.tenancy.AuthorizationContext;

/** Thin delegation, module template. */
@RestController
@RequestMapping("/api/tenant/payroll-batches")
public class PayrollBatchController {

	private final PayrollBatchService payrollBatchService;

	public PayrollBatchController(PayrollBatchService payrollBatchService) {
		this.payrollBatchService = payrollBatchService;
	}

	@RequiresPermission(PermissionKeys.PAYROLL_RUN)
	@PostMapping
	public ResponseEntity<PayrollBatchView> create(HttpServletRequest request, @Valid @RequestBody CreateBatchRequest body) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(payrollBatchService.create(contextFrom(request), body.month(), body.year()));
	}

	@RequiresPermission(PermissionKeys.PAYROLL_RUN)
	@PostMapping("/{batchId}/calculate")
	public PayrollBatchView calculate(HttpServletRequest request, @PathVariable Long batchId) {
		return toResponse(payrollBatchService.calculate(contextFrom(request), batchId));
	}

	@RequiresPermission(PermissionKeys.PAYROLL_RUN)
	@PutMapping("/{batchId}/finalize")
	public PayrollBatchView finalizeBatch(HttpServletRequest request, @PathVariable Long batchId) {
		return toResponse(payrollBatchService.finalizeBatch(contextFrom(request), batchId));
	}

	@RequiresPermission(PermissionKeys.PAYROLL_RUN)
	@PutMapping("/{batchId}/reopen")
	public PayrollBatchView reopen(HttpServletRequest request, @PathVariable Long batchId) {
		return toResponse(payrollBatchService.reopen(contextFrom(request), batchId));
	}

	@RequiresPermission(PermissionKeys.PAYROLL_RUN)
	@DeleteMapping("/{batchId}")
	public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable Long batchId) {
		return switch (payrollBatchService.delete(contextFrom(request), batchId)) {
			case MutationResult.Done done -> ResponseEntity.noContent().build();
			case MutationResult.NotFound notFound -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
			case MutationResult.WrongState wrongState -> throw new ResponseStatusException(HttpStatus.CONFLICT);
		};
	}

	@RequiresPermission(PermissionKeys.PAYROLL_RUN)
	@PutMapping("/{batchId}")
	public PayrollBatchView update(
			HttpServletRequest request, @PathVariable Long batchId, @Valid @RequestBody UpdateBatchPeriodRequest body) {
		return toResponse(payrollBatchService.updatePeriod(contextFrom(request), batchId, body.periodFrom(), body.periodTo()));
	}

	@RequiresPermission(PermissionKeys.PAYROLL_READ)
	@GetMapping("/{batchId}")
	public PayrollBatchView get(HttpServletRequest request, @PathVariable Long batchId) {
		return payrollBatchService.get(contextFrom(request), batchId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.PAYROLL_READ)
	@GetMapping
	public List<PayrollBatchView> list(HttpServletRequest request) {
		return payrollBatchService.list(contextFrom(request));
	}

	private static PayrollBatchView toResponse(MutationResult result) {
		return switch (result) {
			case MutationResult.Done done -> done.view();
			case MutationResult.NotFound notFound -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
			case MutationResult.WrongState wrongState -> throw new ResponseStatusException(HttpStatus.CONFLICT);
		};
	}

	private static AuthorizationContext contextFrom(HttpServletRequest request) {
		Object context = request.getAttribute(AuthorizationContext.class.getName());
		if (context == null) {
			throw new IllegalStateException("AuthorizationContext missing -- authorization interceptor not applied");
		}
		return (AuthorizationContext) context;
	}

}
