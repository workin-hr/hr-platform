package com.workin.backend.advances;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.workin.backend.advances.AdvanceService.TransitionResult;
import com.workin.backend.authorization.PermissionKeys;
import com.workin.backend.authorization.RequiresPermission;
import com.workin.backend.tenancy.AuthorizationContext;

/**
 * Thin delegation, employees-module template. The read/manage/approve
 * split mirrors V4's three advances keys -- creating an advance never
 * implies approving one (legacy bundled all of it behind can_advances,
 * which is how hr-legacy#5's IDOR covered the whole operation set).
 */
@RestController
@RequestMapping("/api/tenant/advances")
public class AdvanceController {

	private final AdvanceService advanceService;

	public AdvanceController(AdvanceService advanceService) {
		this.advanceService = advanceService;
	}

	@RequiresPermission(PermissionKeys.ADVANCES_READ)
	@GetMapping
	public List<AdvanceView> list(HttpServletRequest request) {
		return advanceService.list(contextFrom(request));
	}

	@RequiresPermission(PermissionKeys.ADVANCES_READ)
	@GetMapping("/{advanceId}")
	public AdvanceView get(HttpServletRequest request, @PathVariable Long advanceId) {
		return advanceService.get(contextFrom(request), advanceId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.ADVANCES_MANAGE)
	@PostMapping
	public ResponseEntity<AdvanceView> create(
			HttpServletRequest request, @Valid @RequestBody CreateAdvanceRequest body) {
		return advanceService.create(contextFrom(request), body)
				.map(view -> ResponseEntity.status(HttpStatus.CREATED).body(view))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.ADVANCES_APPROVE)
	@PostMapping("/{advanceId}/approve")
	public AdvanceView approve(HttpServletRequest request, @PathVariable Long advanceId) {
		return toResponse(advanceService.approve(contextFrom(request), advanceId));
	}

	@RequiresPermission(PermissionKeys.ADVANCES_APPROVE)
	@PostMapping("/{advanceId}/reject")
	public AdvanceView reject(
			HttpServletRequest request, @PathVariable Long advanceId,
			@RequestBody(required = false) RejectAdvanceRequest body) {
		String rejectionReason = body == null ? null : body.rejectionReason();
		return toResponse(advanceService.reject(contextFrom(request), advanceId, rejectionReason));
	}

	private static AdvanceView toResponse(TransitionResult result) {
		return switch (result) {
			case TransitionResult.Done done -> done.view();
			case TransitionResult.NotFound notFound -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
			case TransitionResult.WrongState wrongState -> throw new ResponseStatusException(HttpStatus.CONFLICT);
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
