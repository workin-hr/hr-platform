package com.workin.backend.organization;

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
import com.workin.backend.tenancy.AuthorizationContext;

/** Thin delegation, module template. */
@RestController
@RequestMapping("/api/tenant/branches")
public class BranchController {

	private final BranchService branchService;

	public BranchController(BranchService branchService) {
		this.branchService = branchService;
	}

	@RequiresPermission(PermissionKeys.BRANCHES_READ)
	@GetMapping
	public List<BranchView> list(HttpServletRequest request) {
		return branchService.list(contextFrom(request));
	}

	@RequiresPermission(PermissionKeys.BRANCHES_READ)
	@GetMapping("/{branchId}")
	public BranchView get(HttpServletRequest request, @PathVariable Long branchId) {
		return branchService.get(contextFrom(request), branchId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.BRANCHES_MANAGE)
	@PostMapping
	public ResponseEntity<BranchView> create(HttpServletRequest request, @Valid @RequestBody UpsertBranchRequest body) {
		return ResponseEntity.status(HttpStatus.CREATED).body(branchService.create(contextFrom(request), body));
	}

	@RequiresPermission(PermissionKeys.BRANCHES_MANAGE)
	@PutMapping("/{branchId}")
	public BranchView update(
			HttpServletRequest request, @PathVariable Long branchId, @Valid @RequestBody UpsertBranchRequest body) {
		return branchService.update(contextFrom(request), branchId, body)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.BRANCHES_MANAGE)
	@DeleteMapping("/{branchId}")
	public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable Long branchId) {
		if (!branchService.delete(contextFrom(request), branchId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		return ResponseEntity.noContent().build();
	}

	static AuthorizationContext contextFrom(HttpServletRequest request) {
		Object context = request.getAttribute(AuthorizationContext.class.getName());
		if (context == null) {
			throw new IllegalStateException("AuthorizationContext missing -- authorization interceptor not applied");
		}
		return (AuthorizationContext) context;
	}

}
