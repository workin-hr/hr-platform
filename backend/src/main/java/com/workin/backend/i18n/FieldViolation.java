package com.workin.backend.i18n;

/** One bean-validation failure, message localized per request. */
public record FieldViolation(String field, String message) {
}
