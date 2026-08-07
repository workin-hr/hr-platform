package com.workin.backend.attendance;

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

import com.workin.backend.authorization.PermissionKeys;
import com.workin.backend.authorization.RequiresPermission;
import com.workin.backend.tenancy.AuthorizationContext;

/** Thin delegation, module template. */
@RestController
@RequestMapping("/api/tenant/exception-types")
public class ExceptionTypeController {

	private final ExceptionTypeService exceptionTypeService;

	public ExceptionTypeController(ExceptionTypeService exceptionTypeService) {
		this.exceptionTypeService = exceptionTypeService;
	}

	@RequiresPermission(PermissionKeys.ATTENDANCE_READ)
	@GetMapping
	public List<ExceptionTypeView> list(HttpServletRequest request) {
		return exceptionTypeService.list(contextFrom(request));
	}

	@RequiresPermission(PermissionKeys.ATTENDANCE_CORRECT)
	@PostMapping
	public ResponseEntity<ExceptionTypeView> create(
			HttpServletRequest request, @Valid @RequestBody CreateExceptionTypeRequest body) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(exceptionTypeService.create(contextFrom(request), body));
	}

	private static AuthorizationContext contextFrom(HttpServletRequest request) {
		Object context = request.getAttribute(AuthorizationContext.class.getName());
		if (context == null) {
			throw new IllegalStateException("AuthorizationContext missing -- authorization interceptor not applied");
		}
		return (AuthorizationContext) context;
	}

}
