package com.workin.backend.penalties;

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
import com.workin.backend.penalties.PenaltyService.MutationResult;
import com.workin.backend.tenancy.AuthorizationContext;

/** Thin delegation, module template. */
@RestController
@RequestMapping("/api/tenant/penalties")
public class PenaltyController {

	private final PenaltyService penaltyService;

	public PenaltyController(PenaltyService penaltyService) {
		this.penaltyService = penaltyService;
	}

	@RequiresPermission(PermissionKeys.PENALTIES_READ)
	@GetMapping
	public List<PenaltyView> list(HttpServletRequest request) {
		return penaltyService.list(contextFrom(request));
	}

	@RequiresPermission(PermissionKeys.PENALTIES_READ)
	@GetMapping("/{penaltyId}")
	public PenaltyView get(HttpServletRequest request, @PathVariable Long penaltyId) {
		return penaltyService.get(contextFrom(request), penaltyId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.PENALTIES_MANAGE)
	@PostMapping
	public ResponseEntity<PenaltyView> create(
			HttpServletRequest request, @Valid @RequestBody CreatePenaltyRequest body) {
		return penaltyService.create(contextFrom(request), body)
				.map(view -> ResponseEntity.status(HttpStatus.CREATED).body(view))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.PENALTIES_MANAGE)
	@PutMapping("/{penaltyId}")
	public PenaltyView update(
			HttpServletRequest request, @PathVariable Long penaltyId,
			@Valid @RequestBody UpdatePenaltyRequest body) {
		return switch (penaltyService.update(contextFrom(request), penaltyId, body)) {
			case MutationResult.Done done -> done.view();
			case MutationResult.NotFound notFound -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
			case MutationResult.Locked locked -> throw new ResponseStatusException(HttpStatus.CONFLICT);
		};
	}

	@RequiresPermission(PermissionKeys.PENALTIES_MANAGE)
	@DeleteMapping("/{penaltyId}")
	public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable Long penaltyId) {
		return switch (penaltyService.delete(contextFrom(request), penaltyId)) {
			case MutationResult.Done done -> ResponseEntity.noContent().build();
			case MutationResult.NotFound notFound -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
			case MutationResult.Locked locked -> throw new ResponseStatusException(HttpStatus.CONFLICT);
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
