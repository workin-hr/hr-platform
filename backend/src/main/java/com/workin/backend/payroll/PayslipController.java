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
import com.workin.backend.payroll.PayslipService.MutationResult;
import com.workin.backend.tenancy.AuthorizationContext;

/** Thin delegation, module template. */
@RestController
@RequestMapping("/api/tenant/payslips")
public class PayslipController {

	private final PayslipService payslipService;

	public PayslipController(PayslipService payslipService) {
		this.payslipService = payslipService;
	}

	@RequiresPermission(PermissionKeys.PAYROLL_RUN)
	@PostMapping
	public ResponseEntity<PayslipView> create(HttpServletRequest request, @Valid @RequestBody CreatePayslipRequest body) {
		return switch (payslipService.create(contextFrom(request), body.batchId(), body.employeeId(), toAttendanceInput(body))) {
			case MutationResult.Done done -> ResponseEntity.status(HttpStatus.CREATED).body(done.view());
			case MutationResult.NotFound notFound -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
			case MutationResult.WrongState wrongState -> throw new ResponseStatusException(HttpStatus.CONFLICT);
		};
	}

	@RequiresPermission(PermissionKeys.PAYROLL_RUN)
	@PutMapping("/{payslipId}")
	public PayslipView update(HttpServletRequest request, @PathVariable Long payslipId, @Valid @RequestBody UpdatePayslipRequest body) {
		return switch (payslipService.update(contextFrom(request), payslipId, toAttendanceInput(body))) {
			case MutationResult.Done done -> done.view();
			case MutationResult.NotFound notFound -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
			case MutationResult.WrongState wrongState -> throw new ResponseStatusException(HttpStatus.CONFLICT);
		};
	}

	@RequiresPermission(PermissionKeys.PAYROLL_RUN)
	@DeleteMapping("/{payslipId}")
	public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable Long payslipId) {
		return switch (payslipService.delete(contextFrom(request), payslipId)) {
			case MutationResult.Done done -> ResponseEntity.noContent().build();
			case MutationResult.NotFound notFound -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
			case MutationResult.WrongState wrongState -> throw new ResponseStatusException(HttpStatus.CONFLICT);
		};
	}

	@RequiresPermission(PermissionKeys.PAYROLL_READ)
	@GetMapping("/{payslipId}")
	public PayslipView get(HttpServletRequest request, @PathVariable Long payslipId) {
		return payslipService.get(contextFrom(request), payslipId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.PAYROLL_READ)
	@GetMapping
	public List<PayslipView> list(HttpServletRequest request) {
		return payslipService.list(contextFrom(request));
	}

	private static PayslipService.AttendanceInput toAttendanceInput(CreatePayslipRequest body) {
		return new PayslipService.AttendanceInput(body.daysPresent(), body.daysAbsent(), body.daysLeave(), body.overtimeHours());
	}

	private static PayslipService.AttendanceInput toAttendanceInput(UpdatePayslipRequest body) {
		return new PayslipService.AttendanceInput(body.daysPresent(), body.daysAbsent(), body.daysLeave(), body.overtimeHours());
	}

	private static AuthorizationContext contextFrom(HttpServletRequest request) {
		Object context = request.getAttribute(AuthorizationContext.class.getName());
		if (context == null) {
			throw new IllegalStateException("AuthorizationContext missing -- authorization interceptor not applied");
		}
		return (AuthorizationContext) context;
	}

}
