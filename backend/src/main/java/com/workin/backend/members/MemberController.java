package com.workin.backend.members;

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
import com.workin.backend.authorization.ResourceScopeType;
import com.workin.backend.i18n.ApiException;
import com.workin.backend.i18n.MessageKeys;
import com.workin.backend.members.MemberAdminService.MutationResult;
import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantRole;

/** Thin delegation, module template. */
@RestController
@RequestMapping("/api/tenant/members")
public class MemberController {

	private final MemberAdminService memberAdminService;

	public MemberController(MemberAdminService memberAdminService) {
		this.memberAdminService = memberAdminService;
	}

	@RequiresPermission(PermissionKeys.MEMBERS_READ)
	@GetMapping
	public List<MemberView> list(HttpServletRequest request) {
		return memberAdminService.list(contextFrom(request));
	}

	@RequiresPermission(PermissionKeys.MEMBERS_READ)
	@GetMapping("/{membershipId}")
	public MemberDetailView get(HttpServletRequest request, @PathVariable Long membershipId) {
		return memberAdminService.get(contextFrom(request), membershipId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.MEMBERS_MANAGE)
	@PostMapping("/{membershipId}/roles")
	public ResponseEntity<Void> assignRole(
			HttpServletRequest request, @PathVariable Long membershipId, @Valid @RequestBody AssignRoleRequest body) {
		toResponse(memberAdminService.assignRole(contextFrom(request), membershipId, body.role()));
		return ResponseEntity.status(HttpStatus.CREATED).build();
	}

	@RequiresPermission(PermissionKeys.MEMBERS_MANAGE)
	@DeleteMapping("/{membershipId}/roles/{role}")
	public ResponseEntity<Void> removeRole(
			HttpServletRequest request, @PathVariable Long membershipId, @PathVariable TenantRole role) {
		toResponse(memberAdminService.removeRole(contextFrom(request), membershipId, role));
		return ResponseEntity.noContent().build();
	}

	@RequiresPermission(PermissionKeys.MEMBERS_MANAGE)
	@PostMapping("/{membershipId}/permission-overrides")
	public ResponseEntity<Void> grantOverride(
			HttpServletRequest request, @PathVariable Long membershipId, @Valid @RequestBody UpsertOverrideRequest body) {
		toResponse(memberAdminService.grantOverride(contextFrom(request), membershipId, body));
		return ResponseEntity.status(HttpStatus.CREATED).build();
	}

	@RequiresPermission(PermissionKeys.MEMBERS_MANAGE)
	@DeleteMapping("/{membershipId}/permission-overrides/{permissionKey}")
	public ResponseEntity<Void> revokeOverride(
			HttpServletRequest request, @PathVariable Long membershipId, @PathVariable String permissionKey) {
		toResponse(memberAdminService.revokeOverride(contextFrom(request), membershipId, permissionKey));
		return ResponseEntity.noContent().build();
	}

	@RequiresPermission(PermissionKeys.MEMBERS_MANAGE)
	@PutMapping("/{membershipId}/status")
	public ResponseEntity<Void> updateStatus(
			HttpServletRequest request, @PathVariable Long membershipId, @Valid @RequestBody UpdateStatusRequest body) {
		toResponse(memberAdminService.updateStatus(contextFrom(request), membershipId, body.status()));
		return ResponseEntity.ok().build();
	}

	@RequiresPermission(PermissionKeys.MEMBERS_READ)
	@GetMapping("/{membershipId}/resource-scopes")
	public List<ResourceScopeView> listScopes(HttpServletRequest request, @PathVariable Long membershipId) {
		return memberAdminService.listScopes(contextFrom(request), membershipId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.MEMBERS_MANAGE)
	@PostMapping("/{membershipId}/resource-scopes")
	public ResponseEntity<Void> assignScope(
			HttpServletRequest request, @PathVariable Long membershipId, @Valid @RequestBody AssignScopeRequest body) {
		toResponse(memberAdminService.assignScope(contextFrom(request), membershipId, body));
		return ResponseEntity.status(HttpStatus.CREATED).build();
	}

	@RequiresPermission(PermissionKeys.MEMBERS_MANAGE)
	@DeleteMapping("/{membershipId}/resource-scopes/{scopeType}/{scopeId}")
	public ResponseEntity<Void> revokeScope(
			HttpServletRequest request, @PathVariable Long membershipId,
			@PathVariable ResourceScopeType scopeType, @PathVariable Long scopeId) {
		toResponse(memberAdminService.revokeScope(contextFrom(request), membershipId, scopeType, scopeId));
		return ResponseEntity.noContent().build();
	}

	private static void toResponse(MutationResult result) {
		switch (result) {
			case MutationResult.Done done -> {
			}
			case MutationResult.NotFound notFound -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
			case MutationResult.SelfMutation selfMutation ->
					throw new ApiException(HttpStatus.CONFLICT, MessageKeys.MEMBERS_OWN_MEMBERSHIP);
			case MutationResult.LastAdmin lastAdmin ->
					throw new ApiException(HttpStatus.CONFLICT, MessageKeys.MEMBERS_LAST_ADMIN);
			case MutationResult.Duplicate duplicate ->
					throw new ApiException(HttpStatus.CONFLICT, MessageKeys.MEMBERS_ALREADY_PRESENT);
		}
	}

	private static AuthorizationContext contextFrom(HttpServletRequest request) {
		Object context = request.getAttribute(AuthorizationContext.class.getName());
		if (context == null) {
			throw new IllegalStateException("AuthorizationContext missing -- authorization interceptor not applied");
		}
		return (AuthorizationContext) context;
	}

}
