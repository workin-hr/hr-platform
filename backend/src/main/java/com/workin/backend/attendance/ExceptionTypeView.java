package com.workin.backend.attendance;

public record ExceptionTypeView(Long id, String name) {

	static ExceptionTypeView of(ExceptionType exceptionType) {
		return new ExceptionTypeView(exceptionType.getId(), exceptionType.getName());
	}

}
