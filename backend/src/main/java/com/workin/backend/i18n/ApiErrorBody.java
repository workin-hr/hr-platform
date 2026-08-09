package com.workin.backend.i18n;

/** The wire error contract: stable code + display-ready localized message. */
public record ApiErrorBody(String code, String message) {
}
