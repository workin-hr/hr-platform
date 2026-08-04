package com.workin.spike.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.workin.spike.referencedata.BranchRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

/**
 * H2 spike, repository-guard arm. No RLS exists in this database at all
 * (application-isolation-guard.properties). GuardBranchService's
 * explicit company_id-scoped queries block cross-tenant access when used
 * correctly -- but {@link #forgettingToScopeLeaksCrossTenantData()}
 * proves the discipline-dependence risk this arm is named for: nothing
 * in the database itself stops a mistaken, unscoped query from leaking
 * data, unlike the RLS arm.
 */
@ActiveProfiles("isolation-guard")
class GuardCrossTenantIsolationTest extends AbstractCrossTenantIsolationTest {

    @Autowired
    private BranchRepository branchRepository;

    @Test
    @DisplayName("Guard: company A cannot read company B's branch by ID, via the correctly-scoped controller path")
    void companyACannotReadCompanyBsBranchById() throws Exception {
        mockMvc.perform(get("/api/branches/" + companyBBranchId).header("Authorization", "Bearer " + companyAToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Guard: company A's branch list never includes company B's branch, via the correctly-scoped controller path")
    void companyAsBranchListExcludesCompanyBsBranch() throws Exception {
        mockMvc.perform(get("/api/branches").header("Authorization", "Bearer " + companyAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("Guard: a query that forgets to scope by company_id leaks cross-tenant data -- the discipline-dependence risk")
    void forgettingToScopeLeaksCrossTenantData() {
        // Deliberately calls the repository's UNSCOPED findById, exactly
        // as if a developer had written `branchRepository.findById(id)`
        // instead of `findByIdAndCompanyId(id, currentCompanyId)` -- the
        // real shape of hr-legacy#2/#3/#5/#6 (a missing explicit check,
        // not a missing database-level control). Because this database
        // has no RLS at all (isolation-guard profile), nothing catches
        // this mistake.
        var leaked = branchRepository.findById(companyBBranchId);

        assertTrue(leaked.isPresent());
        assertEquals(companyBId, leaked.get().getCompanyId()); // company B's data, visible with no company A context at all
    }
}
