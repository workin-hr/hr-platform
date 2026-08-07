package com.workin.backend.payroll;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreateBatchRequest(@NotNull @Min(1) @Max(12) Short month, @NotNull Short year) {
}
