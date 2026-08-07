package com.workin.backend.requests;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.workin.backend.authorization.PermissionKeys;
import com.workin.backend.authorization.RequiresPermission;
import com.workin.backend.tenancy.AuthorizationContext;

/** Thin delegation, module template. Update/delete deferred per the slice spec. */
@RestController
@RequestMapping("/api/tenant/request-types")
public class RequestTypeController {

	private final RequestTypeService requestTypeService;

	public RequestTypeController(RequestTypeService requestTypeService) {
		this.requestTypeService = requestTypeService;
	}

	@RequiresPermission(PermissionKeys.REQUESTS_READ)
	@GetMapping
	public List<RequestTypeView> list(HttpServletRequest request) {
		return requestTypeService.list(contextFrom(request));
	}

	@RequiresPermission(PermissionKeys.REQUESTS_MANAGE)
	@PostMapping
	public ResponseEntity<RequestTypeView> create(
			HttpServletRequest request, @Valid @RequestBody CreateRequestTypeRequest body) {
		return requestTypeService.create(contextFrom(request), body)
				.map(view -> ResponseEntity.status(HttpStatus.CREATED).body(view))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	private static AuthorizationContext contextFrom(HttpServletRequest request) {
		Object context = request.getAttribute(AuthorizationContext.class.getName());
		if (context == null) {
			throw new IllegalStateException("AuthorizationContext missing -- authorization interceptor not applied");
		}
		return (AuthorizationContext) context;
	}

}
