package com.workin.spike.tenancy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

/**
 * H2 spike, RLS arm. RlsBranchService (referencedata.isolation) uses the
 * repository's UNSCOPED findAll/findById -- if this test passes, it is
 * PostgreSQL Row-Level Security alone doing the blocking, not any
 * explicit company_id check in application code.
 */
@ActiveProfiles("isolation-rls")
class RlsCrossTenantIsolationTest extends AbstractCrossTenantIsolationTest {

    @Test
    @DisplayName("RLS: company A cannot read company B's branch by ID, even via an unscoped repository call")
    void companyACannotReadCompanyBsBranchById() throws Exception {
        mockMvc.perform(get("/api/branches/" + companyBBranchId).header("Authorization", "Bearer " + companyAToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("RLS: company A's branch list never includes company B's branch")
    void companyAsBranchListExcludesCompanyBsBranch() throws Exception {
        mockMvc.perform(get("/api/branches").header("Authorization", "Bearer " + companyAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("RLS: company B can still read its own branch (isolation is not overly broad)")
    void companyBCanReadItsOwnBranch() throws Exception {
        mockMvc.perform(get("/api/branches/" + companyBBranchId).header("Authorization", "Bearer " + companyBToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyId").value(companyBId));
    }
}
