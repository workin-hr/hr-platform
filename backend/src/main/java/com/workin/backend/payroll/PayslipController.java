package com.workin.backend.payroll;

import java.math.BigDecimal;
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
 * Class-level {@code @Transactional} -- see {@code AdvanceController}'s
 * Javadoc for why resolving the tenant context and the subsequent
 * service call must share one transaction.
 */
@RestController
@Transactional
public class PayslipController {

	public record CreatePayslipRequest(
			@NotNull Long batchId, @NotNull Long employeeId,
			int daysPresent, int daysAbsent, int daysLeave, BigDecimal overtimeHours) {

		PayslipService.AttendanceInput toAttendanceInput() {
			return new PayslipService.AttendanceInput(daysPresent, daysAbsent, daysLeave, overtimeHours);
		}
	}

	public record UpdatePayslipRequest(int daysPresent, int daysAbsent, int daysLeave, BigDecimal overtimeHours) {

		PayslipService.AttendanceInput toAttendanceInput() {
			return new PayslipService.AttendanceInput(daysPresent, daysAbsent, daysLeave, overtimeHours);
		}
	}

	public record PayslipResponse(
			Long id, Long batchId, Long employeeId, int daysPresent, int daysAbsent, int daysLeave,
			BigDecimal overtimeHours, BigDecimal basicSalary, BigDecimal housingAllowance, BigDecimal overtimePay,
			BigDecimal foodAllowance, BigDecimal riskAllowance, BigDecimal transportAllowance, BigDecimal incentives,
			BigDecimal penaltiesTotal, BigDecimal advanceDeduction, BigDecimal otherDeductions,
			BigDecimal grossSalary, BigDecimal totalEntitlements, BigDecimal totalDeductions, BigDecimal netSalary) {

		static PayslipResponse from(Payslip p) {
			return new PayslipResponse(
					p.getId(), p.getBatchId(), p.getEmployeeId(), p.getDaysPresent(), p.getDaysAbsent(), p.getDaysLeave(),
					p.getOvertimeHours(), p.getBasicSalary(), p.getHousingAllowance(), p.getOvertimePay(),
					p.getFoodAllowance(), p.getRiskAllowance(), p.getTransportAllowance(), p.getIncentives(),
					p.getPenaltiesTotal(), p.getAdvanceDeduction(), p.getOtherDeductions(),
					p.getGrossSalary(), p.getTotalEntitlements(), p.getTotalDeductions(), p.getNetSalary());
		}
	}

	private final PayslipService payslipService;
	private final AuthorizationContextResolver authorizationContextResolver;

	public PayslipController(PayslipService payslipService, AuthorizationContextResolver authorizationContextResolver) {
		this.payslipService = payslipService;
		this.authorizationContextResolver = authorizationContextResolver;
	}

	@PostMapping("/api/payroll/payslips")
	public ResponseEntity<PayslipResponse> create(@Valid @RequestBody CreatePayslipRequest request) {
		AuthorizationContext context = authorizationContextResolver.resolve();
		Payslip created = payslipService.create(context, request.batchId(), request.employeeId(), request.toAttendanceInput());
		return ResponseEntity.status(HttpStatus.CREATED).body(PayslipResponse.from(created));
	}

	@PutMapping("/api/payroll/payslips/{id}")
	public PayslipResponse update(@PathVariable Long id, @Valid @RequestBody UpdatePayslipRequest request) {
		AuthorizationContext context = authorizationContextResolver.resolve();
		return PayslipResponse.from(payslipService.update(context, id, request.toAttendanceInput()));
	}

	@DeleteMapping("/api/payroll/payslips/{id}")
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		AuthorizationContext context = authorizationContextResolver.resolve();
		payslipService.delete(context, id);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/api/payroll/payslips/{id}")
	public PayslipResponse one(@PathVariable Long id) {
		AuthorizationContext context = authorizationContextResolver.resolve();
		return PayslipResponse.from(payslipService.findOne(context, id));
	}

	@GetMapping("/api/payroll/payslips")
	public List<PayslipResponse> list() {
		AuthorizationContext context = authorizationContextResolver.resolve();
		return payslipService.list(context).stream().map(PayslipResponse::from).toList();
	}

}
