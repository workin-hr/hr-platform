package com.workin.legacy.organization;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Legacy department read shape. Create/update omit {@code company_id} because their PHP response
 * queries omit it; {@link JsonInclude} keeps that endpoint-specific difference on the wire.
 */
public record LegacyDepartmentView(
		Long id,
		@JsonProperty("company_id") @JsonInclude(JsonInclude.Include.NON_NULL) Long companyId,
		@JsonProperty("manager_id") Long managerId,
		String name,
		@JsonProperty("is_active") boolean isActive,
		@JsonProperty("created_at") Instant createdAt,
		@JsonProperty("branch_ids") String branchIds,
		@JsonProperty("branch_names") String branchNames,
		@JsonProperty("manager_name") String managerName) {
}
