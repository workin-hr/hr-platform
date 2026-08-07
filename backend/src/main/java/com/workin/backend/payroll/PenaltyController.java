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
import org.springframework.web.bind.annotation.RequestParam;
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
public class PenaltyController {

	public record UpsertPenaltyRequest(
			@NotNull String penaltyType, @NotNull BigDecimal penaltyDays, String reason, @NotNull LocalDate penaltyDate) {

		PenaltyService.PenaltyFields toFields() {
			return new PenaltyService.PenaltyFields(penaltyType, penaltyDays, reason, penaltyDate);
		}
	}

	public record PenaltyResponse(
			Long id, Long employeeId, String penaltyType, BigDecimal penaltyDays, String reason,
			LocalDate penaltyDate, boolean appliedToPayroll) {

		static PenaltyResponse from(Penalty p) {
			return new PenaltyResponse(
					p.getId(), p.getEmployeeId(), p.getPenaltyType(), p.getPenaltyDays(), p.getReason(),
					p.getPenaltyDate(), p.isAppliedToPayroll());
		}
	}

	private final PenaltyService penaltyService;
	private final AuthorizationContextResolver authorizationContextResolver;

	public PenaltyController(PenaltyService penaltyService, AuthorizationContextResolver authorizationContextResolver) {
		this.penaltyService = penaltyService;
		this.authorizationContextResolver = authorizationContextResolver;
	}

	@PostMapping("/api/payroll/penalties")
	public ResponseEntity<PenaltyResponse> create(
			@RequestParam Long employeeId, @Valid @RequestBody UpsertPenaltyRequest request) {
		AuthorizationContext context = authorizationContextResolver.resolve();
		Penalty created = penaltyService.create(context, employeeId, request.toFields());
		return ResponseEntity.status(HttpStatus.CREATED).body(PenaltyResponse.from(created));
	}

	@PutMapping("/api/payroll/penalties/{id}")
	public PenaltyResponse update(@PathVariable Long id, @Valid @RequestBody UpsertPenaltyRequest request) {
		AuthorizationContext context = authorizationContextResolver.resolve();
		return PenaltyResponse.from(penaltyService.update(context, id, request.toFields()));
	}

	@DeleteMapping("/api/payroll/penalties/{id}")
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		AuthorizationContext context = authorizationContextResolver.resolve();
		penaltyService.delete(context, id);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/api/payroll/penalties/{id}")
	public PenaltyResponse one(@PathVariable Long id) {
		AuthorizationContext context = authorizationContextResolver.resolve();
		return PenaltyResponse.from(penaltyService.findOne(context, id));
	}

	@GetMapping("/api/payroll/penalties")
	public List<PenaltyResponse> list() {
		AuthorizationContext context = authorizationContextResolver.resolve();
		return penaltyService.list(context).stream().map(PenaltyResponse::from).toList();
	}

}
