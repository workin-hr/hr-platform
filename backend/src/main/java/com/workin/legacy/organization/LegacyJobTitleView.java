package com.workin.legacy.organization;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code jt.*} plus the two joined/aggregated fields returned by all four job-title read shapes. */
public record LegacyJobTitleView(
		Long id,
		@JsonProperty("company_id") Long companyId,
		@JsonProperty("department_id") Long departmentId,
		String name,
		@JsonProperty("work_hours") BigDecimal workHours,
		@JsonProperty("is_active") boolean isActive,
		@JsonProperty("created_at") Instant createdAt,
		@JsonProperty("department_name") String departmentName,
		@JsonProperty("branches_summary") String branchesSummary) {
}
