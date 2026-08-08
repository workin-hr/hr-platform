package com.workin.backend.i18n;

import org.springframework.http.HttpStatus;

/**
 * The one application exception for keyed, localizable API errors
 * (spec: error contract). The key is stable for programmatic client
 * handling; ApiExceptionHandler localizes it per request.
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;
	private final transient Object[] args;

	public ApiException(HttpStatus status, String messageKey, Object... args) {
		super(messageKey);
		this.status = status;
		this.code = messageKey;
		this.args = args;
	}

	public ApiException(HttpStatus status, String messageKey, Throwable cause, Object... args) {
		super(messageKey, cause);
		this.status = status;
		this.code = messageKey;
		this.args = args;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}

	public Object[] getArgs() {
		return args;
	}

}
