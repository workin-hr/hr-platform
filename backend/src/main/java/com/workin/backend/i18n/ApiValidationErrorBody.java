package com.workin.backend.i18n;

import java.util.List;

/** Validation-error body: the generic envelope plus per-field detail. */
public record ApiValidationErrorBody(String code, String message, List<FieldViolation> fields) {
}
