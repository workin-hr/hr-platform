package com.workin.backend.tenancy;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * docs/architecture/authorization-model.md §8: "all access-denied
 * responses must avoid revealing whether an inaccessible cross-tenant
 * resource exists." Every {@link TenantContextException} -- membership
 * not found, disabled, or belonging to a different identity -- becomes
 * the same uniform 404, never a distinguishable 403-vs-404 signal.
 */
@RestControllerAdvice
public class TenantContextExceptionHandler {

	@ExceptionHandler(TenantContextException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public void handle(TenantContextException ex) {
		// Intentionally empty body -- see class Javadoc.
	}

}
