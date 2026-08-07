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

/** Thin delegation, module template. */
@RestController
@RequestMapping("/api/tenant/departments")
public class DepartmentController {

	private final DepartmentService departmentService;

	public DepartmentController(DepartmentService departmentService) {
		this.departmentService = departmentService;
	}

	@RequiresPermission(PermissionKeys.DEPARTMENTS_READ)
	@GetMapping
	public List<DepartmentView> list(HttpServletRequest request) {
		return departmentService.list(BranchController.contextFrom(request));
	}

	@RequiresPermission(PermissionKeys.DEPARTMENTS_READ)
	@GetMapping("/{departmentId}")
	public DepartmentView get(HttpServletRequest request, @PathVariable Long departmentId) {
		return departmentService.get(BranchController.contextFrom(request), departmentId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.DEPARTMENTS_MANAGE)
	@PostMapping
	public ResponseEntity<DepartmentView> create(
			HttpServletRequest request, @Valid @RequestBody UpsertDepartmentRequest body) {
		return departmentService.create(BranchController.contextFrom(request), body)
				.map(view -> ResponseEntity.status(HttpStatus.CREATED).body(view))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.DEPARTMENTS_MANAGE)
	@PutMapping("/{departmentId}")
	public DepartmentView update(
			HttpServletRequest request, @PathVariable Long departmentId,
			@Valid @RequestBody UpsertDepartmentRequest body) {
		return departmentService.update(BranchController.contextFrom(request), departmentId, body)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.DEPARTMENTS_MANAGE)
	@DeleteMapping("/{departmentId}")
	public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable Long departmentId) {
		if (!departmentService.delete(BranchController.contextFrom(request), departmentId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		return ResponseEntity.noContent().build();
	}

}
