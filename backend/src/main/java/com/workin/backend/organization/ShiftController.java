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
@RequestMapping("/api/tenant/shifts")
public class ShiftController {

	private final ShiftService shiftService;

	public ShiftController(ShiftService shiftService) {
		this.shiftService = shiftService;
	}

	@RequiresPermission(PermissionKeys.SHIFTS_READ)
	@GetMapping
	public List<ShiftView> list(HttpServletRequest request) {
		return shiftService.list(BranchController.contextFrom(request));
	}

	@RequiresPermission(PermissionKeys.SHIFTS_READ)
	@GetMapping("/{shiftId}")
	public ShiftView get(HttpServletRequest request, @PathVariable Long shiftId) {
		return shiftService.get(BranchController.contextFrom(request), shiftId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.SHIFTS_MANAGE)
	@PostMapping
	public ResponseEntity<ShiftView> create(HttpServletRequest request, @Valid @RequestBody UpsertShiftRequest body) {
		return ResponseEntity.status(HttpStatus.CREATED).body(shiftService.create(BranchController.contextFrom(request), body));
	}

	@RequiresPermission(PermissionKeys.SHIFTS_MANAGE)
	@PutMapping("/{shiftId}")
	public ShiftView update(
			HttpServletRequest request, @PathVariable Long shiftId, @Valid @RequestBody UpsertShiftRequest body) {
		return shiftService.update(BranchController.contextFrom(request), shiftId, body)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.SHIFTS_MANAGE)
	@DeleteMapping("/{shiftId}")
	public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable Long shiftId) {
		if (!shiftService.delete(BranchController.contextFrom(request), shiftId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		return ResponseEntity.noContent().build();
	}

}
