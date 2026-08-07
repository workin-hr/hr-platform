package com.workin.backend.payroll;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class PayrollExceptionHandler {

	@ExceptionHandler(PayrollNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public void handleNotFound(PayrollNotFoundException ex) {
		// Intentionally empty body -- see PayrollNotFoundException Javadoc.
	}

	@ExceptionHandler(PayrollForbiddenException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public void handleForbidden(PayrollForbiddenException ex) {
	}

	/**
	 * Every business-rule violation in this module (draft-only mutation
	 * locks, overpayment, editing an already-applied-to-payroll penalty,
	 * etc.) is raised as this standard exception rather than a bespoke
	 * type per rule -- without this handler it would otherwise surface
	 * as an uncaught 500.
	 */
	@ExceptionHandler(IllegalArgumentException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public void handleBadRequest(IllegalArgumentException ex) {
	}

	/**
	 * Translates the real DB-level uniqueness constraints added in this
	 * module (payroll_batches (company_id, month, year); payslips
	 * (batch_id, employee_id)) into a clean conflict response, instead of
	 * reproducing the legacy pattern of an app-level pre-check that
	 * leaves a race window open --
	 * docs/migration/payroll-module-execution-plan.md, PayrollBatch
	 * module section.
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public void handleConflict(DataIntegrityViolationException ex) {
	}

}
