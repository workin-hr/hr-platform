package com.workin.spike.referencedata;

import jakarta.validation.constraints.NotBlank;

public record CreateBranchRequest(@NotBlank String name) {
}
