package com.workin.backend.payroll;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

public record UpdateBatchPeriodRequest(@NotNull LocalDate periodFrom, @NotNull LocalDate periodTo) {
}
