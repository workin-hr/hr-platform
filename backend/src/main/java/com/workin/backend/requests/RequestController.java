package com.workin.backend.requests;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.workin.backend.authorization.PermissionKeys;
import com.workin.backend.authorization.RequiresPermission;
import com.workin.backend.requests.RequestService.MutationResult;
import com.workin.backend.tenancy.AuthorizationContext;

/**
 * Thin delegation, module template. Date ordering is request-shape
 * validation: rejected here with a 400 before the service runs, the
 * same altitude as jakarta @Valid.
 */
@RestController
@RequestMapping("/api/tenant/requests")
public class RequestController {

	private final RequestService requestService;

	public RequestController(RequestService requestService) {
		this.requestService = requestService;
	}

	@RequiresPermission(PermissionKeys.REQUESTS_READ)
	@GetMapping
	public List<RequestView> list(
			HttpServletRequest request,
			@RequestParam(required = false) Long employeeId,
			@RequestParam(required = false) RequestStatus status) {
		return requestService.list(contextFrom(request), employeeId, status);
	}

	@RequiresPermission(PermissionKeys.REQUESTS_READ)
	@GetMapping("/{requestId}")
	public RequestView get(HttpServletRequest request, @PathVariable Long requestId) {
		return requestService.get(contextFrom(request), requestId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.REQUESTS_MANAGE)
	@PostMapping
	public ResponseEntity<RequestView> create(HttpServletRequest request, @Valid @RequestBody CreateRequestRequest body) {
		requireOrderedDates(body.fromDate(), body.toDate());
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(toResponse(requestService.create(contextFrom(request), body)));
	}

	@RequiresPermission(PermissionKeys.REQUESTS_MANAGE)
	@PutMapping("/{requestId}")
	public RequestView update(
			HttpServletRequest request, @PathVariable Long requestId, @Valid @RequestBody UpdateRequestRequest body) {
		requireOrderedDates(body.fromDate(), body.toDate());
		return toResponse(requestService.update(contextFrom(request), requestId, body));
	}

	@RequiresPermission(PermissionKeys.REQUESTS_MANAGE)
	@DeleteMapping("/{requestId}")
	public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable Long requestId) {
		toResponse(requestService.delete(contextFrom(request), requestId));
		return ResponseEntity.noContent().build();
	}

	@RequiresPermission(PermissionKeys.REQUESTS_APPROVE)
	@PutMapping("/{requestId}/approve")
	public RequestView approve(
			HttpServletRequest request, @PathVariable Long requestId, @Valid @RequestBody ApproveRequestRequest body) {
		return toResponse(requestService.approve(contextFrom(request), requestId, body.reply()));
	}

	@RequiresPermission(PermissionKeys.REQUESTS_APPROVE)
	@PutMapping("/{requestId}/reject")
	public RequestView reject(
			HttpServletRequest request, @PathVariable Long requestId, @Valid @RequestBody RejectRequestRequest body) {
		return toResponse(requestService.reject(contextFrom(request), requestId, body.reply()));
	}

	private static void requireOrderedDates(java.time.LocalDate from, java.time.LocalDate to) {
		if (to.isBefore(from)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "toDate must not precede fromDate");
		}
	}

	private static RequestView toResponse(MutationResult result) {
		return switch (result) {
			case MutationResult.Done done -> done.view();
			case MutationResult.NotFound notFound -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
			case MutationResult.WrongState wrongState -> throw new ResponseStatusException(HttpStatus.CONFLICT);
			case MutationResult.InsufficientBalance insufficientBalance ->
					throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "insufficient leave balance");
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
