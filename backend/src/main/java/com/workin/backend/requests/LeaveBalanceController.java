package com.workin.backend.requests;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.workin.backend.authorization.PermissionKeys;
import com.workin.backend.authorization.RequiresPermission;
import com.workin.backend.requests.LeaveBalanceService.MutationResult;
import com.workin.backend.tenancy.AuthorizationContext;

/** Thin delegation, module template. No DELETE (deferred per the slice spec). */
@RestController
@RequestMapping("/api/tenant/leave-balances")
public class LeaveBalanceController {

	private final LeaveBalanceService leaveBalanceService;

	public LeaveBalanceController(LeaveBalanceService leaveBalanceService) {
		this.leaveBalanceService = leaveBalanceService;
	}

	@RequiresPermission(PermissionKeys.LEAVE_BALANCES_READ)
	@GetMapping
	public List<LeaveBalanceView> list(
			HttpServletRequest request,
			@RequestParam(required = false) Long employeeId,
			@RequestParam(required = false) Short year) {
		return leaveBalanceService.list(contextFrom(request), employeeId, year);
	}

	@RequiresPermission(PermissionKeys.LEAVE_BALANCES_READ)
	@GetMapping("/{balanceId}")
	public LeaveBalanceView get(HttpServletRequest request, @PathVariable Long balanceId) {
		return leaveBalanceService.get(contextFrom(request), balanceId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.LEAVE_BALANCES_MANAGE)
	@PostMapping
	public ResponseEntity<LeaveBalanceView> create(
			HttpServletRequest request, @Valid @RequestBody CreateLeaveBalanceRequest body) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(toResponse(leaveBalanceService.create(contextFrom(request), body)));
	}

	@RequiresPermission(PermissionKeys.LEAVE_BALANCES_MANAGE)
	@PutMapping("/{balanceId}")
	public LeaveBalanceView update(
			HttpServletRequest request, @PathVariable Long balanceId,
			@Valid @RequestBody UpdateLeaveBalanceRequest body) {
		return toResponse(leaveBalanceService.update(contextFrom(request), balanceId, body));
	}

	private static LeaveBalanceView toResponse(MutationResult result) {
		return switch (result) {
			case MutationResult.Done done -> done.view();
			case MutationResult.NotFound notFound -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
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
